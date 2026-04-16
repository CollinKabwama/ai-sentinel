package io.aisentinel.autoconfigure.identity.trust;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.identity.trust.BehavioralBaselineEntry;
import io.aisentinel.core.identity.trust.BehavioralBaselineStore;
import io.aisentinel.core.identity.trust.IdentityBehavioralBaselineStore;
import io.aisentinel.core.metrics.SentinelMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed {@link BehavioralBaselineStore} for identity behavioral state with in-memory fail-open fallback
 * (same semantics as {@link IdentityBehavioralBaselineStore} on errors).
 * <p>
 * Updates are applied atomically server-side via Lua (read-merge-write). The caller waits at most
 * {@link SentinelProperties.TrustDistributed#getCommandTimeout()} on a {@link CompletableFuture}; that does not
 * cancel in-flight Lettuce I/O—configure {@code spring.data.redis.timeout} to align with this budget.
 */
@Slf4j
public final class RedisFailOpenBehavioralBaselineStore implements BehavioralBaselineStore, DisposableBean {

    /**
     * Atomic GET → merge one observation → SET with PX; returns previous JSON (bulk) or empty string if none.
     * Uses Redis {@code cjson} (same field names as Jackson payload).
     */
    private static final String ATOMIC_UPDATE_SCRIPT = """
            local cjson = cjson
            local key = KEYS[1]
            local ttl_ms = tonumber(ARGV[1])
            local now_ms = tonumber(ARGV[2])
            local endpoint = ARGV[3] or ''
            local fp = tonumber(ARGV[4])
            local ip = tonumber(ARGV[5])
            local max_ep = 512
            if #endpoint > max_ep then
              endpoint = string.sub(endpoint, 1, max_ep)
            end
            local raw = redis.call('GET', key)
            if raw == false then
              raw = nil
            end
            local before_out = raw
            if raw == nil or raw == '' then
              local new_e = {
                lastSeenMs = now_ms,
                observationCount = 1,
                lastEndpoint = endpoint,
                lastHeaderFingerprintHash = fp,
                lastIpBucket = ip
              }
              redis.call('SET', key, cjson.encode(new_e), 'PX', ttl_ms)
              return ''
            end
            local before = cjson.decode(raw)
            local oc = before.observationCount
            if oc == nil then
              oc = 0
            end
            local new_e = {
              lastSeenMs = now_ms,
              observationCount = oc + 1,
              lastEndpoint = endpoint,
              lastHeaderFingerprintHash = fp,
              lastIpBucket = ip
            }
            redis.call('SET', key, cjson.encode(new_e), 'PX', ttl_ms)
            return before_out
            """;

    private static final DefaultRedisScript<String> ATOMIC_SCRIPT = new DefaultRedisScript<>();

    static {
        ATOMIC_SCRIPT.setScriptText(ATOMIC_UPDATE_SCRIPT);
        ATOMIC_SCRIPT.setResultType(String.class);
    }

    private final StringRedisTemplate redis;
    private final IdentityBehavioralBaselineStore fallback;
    private final String keyPrefix;
    private final long ttlMs;
    private final long commandTimeoutMs;
    private final SentinelMetrics metrics;
    private final ObjectMapper json = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final ExecutorService redisCommandExecutor;

    public RedisFailOpenBehavioralBaselineStore(StringRedisTemplate redis,
                                                IdentityBehavioralBaselineStore fallback,
                                                SentinelProperties sentinelProperties,
                                                SentinelMetrics metrics) {
        this.redis = redis;
        this.fallback = fallback;
        SentinelProperties.TrustDistributed d = sentinelProperties.getIdentity().getTrust().getDistributed();
        String p = d.getKeyPrefix() != null ? d.getKeyPrefix().trim() : "";
        this.keyPrefix = p.isEmpty() ? "aisentinel:trust:bl:" : p;
        this.ttlMs = Math.max(1_000L, sentinelProperties.getIdentity().getTrust().getBaselineTtl().toMillis());
        Duration ct = d.getCommandTimeout();
        long ms = ct != null && !ct.isNegative() ? ct.toMillis() : 50L;
        this.commandTimeoutMs = ms > 0 ? ms : 50L;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
        this.redisCommandExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public void destroy() {
        redisCommandExecutor.shutdown();
        try {
            if (!redisCommandExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                redisCommandExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            redisCommandExecutor.shutdownNow();
        }
    }

    @Override
    public BehavioralBaselineEntry updateAndGetPrevious(String key, String endpoint, long headerFingerprintHash,
                                                        int ipBucket, long nowMillis) {
        try {
            String rkey = redisKey(keyPrefix, key);
            String previousRaw = evalAtomicWithTimeout(rkey, endpoint, headerFingerprintHash, ipBucket, nowMillis);
            BehavioralBaselineEntry before =
                previousRaw == null || previousRaw.isBlank() ? null : fromJson(previousRaw);
            metrics.recordTrustBaselineRedisSuccess();
            return before;
        } catch (Exception e) {
            return onRedisFailure(key, e, endpoint, headerFingerprintHash, ipBucket, nowMillis);
        }
    }

    private String evalAtomicWithTimeout(String rkey,
                                         String endpoint,
                                         long headerFingerprintHash,
                                         int ipBucket,
                                         long nowMillis) throws Exception {
        String ep = endpoint != null ? endpoint : "";
        List<String> keys = List.of(rkey);
        CompletableFuture<String> fut;
        try {
            fut = CompletableFuture.supplyAsync(
                () -> redis.execute(
                    ATOMIC_SCRIPT,
                    keys,
                    String.valueOf(ttlMs),
                    String.valueOf(nowMillis),
                    ep,
                    String.valueOf(headerFingerprintHash),
                    String.valueOf(ipBucket)),
                redisCommandExecutor);
        } catch (RejectedExecutionException ex) {
            throw ex;
        }
        try {
            return fut.get(commandTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            if (c instanceof Error err) {
                throw err;
            }
            if (c instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private BehavioralBaselineEntry onRedisFailure(String logicalKey,
                                                   Throwable e,
                                                   String endpoint,
                                                   long headerFingerprintHash,
                                                   int ipBucket,
                                                   long nowMillis) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        log.debug("Trust baseline Redis failed for key {}: {}", logicalKey, e.getMessage());
        metrics.recordTrustBaselineRedisFailure();
        metrics.recordTrustBaselineRedisFallback();
        return fallback.updateAndGetPrevious(logicalKey, endpoint, headerFingerprintHash, ipBucket, nowMillis);
    }

    static String redisKey(String prefix, String logicalKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(logicalKey.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private BehavioralBaselineEntry fromJson(String raw) throws JsonProcessingException {
        Payload p = json.readValue(raw, Payload.class);
        BehavioralBaselineEntry e = new BehavioralBaselineEntry();
        e.lastSeenMs = p.lastSeenMs;
        e.observationCount = p.observationCount;
        e.lastEndpoint = p.lastEndpoint != null ? p.lastEndpoint : "";
        e.lastHeaderFingerprintHash = p.lastHeaderFingerprintHash;
        e.lastIpBucket = p.lastIpBucket;
        return e;
    }

    private static final class Payload {
        public long lastSeenMs;
        public long observationCount;
        public String lastEndpoint = "";
        public long lastHeaderFingerprintHash;
        public int lastIpBucket = Integer.MIN_VALUE;
    }
}
