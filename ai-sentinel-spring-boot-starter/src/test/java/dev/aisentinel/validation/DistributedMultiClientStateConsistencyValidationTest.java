package dev.aisentinel.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.autoconfigure.distributed.DistributedQuarantineKeyBuilder;
import dev.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import dev.aisentinel.autoconfigure.distributed.DistributedThrottleKeyBuilder;
import dev.aisentinel.autoconfigure.distributed.DistributedThrottleStatus;
import dev.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader;
import dev.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineWriter;
import dev.aisentinel.autoconfigure.distributed.throttle.RedisClusterThrottleStore;
import dev.aisentinel.autoconfigure.identity.trust.RedisFailOpenBehavioralBaselineStore;
import dev.aisentinel.core.identity.trust.BehavioralBaselineEntry;
import dev.aisentinel.core.identity.trust.IdentityBehavioralBaselineStore;
import dev.aisentinel.core.metrics.SentinelMetrics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Same-version multi-client Redis state consistency / divergence evidence (single JVM, two independent
 * Lettuce clients against one ephemeral {@code redis:7-alpine} container started via the Docker CLI —
 * same image as Testcontainers / deployment Redis helpers).
 * <p>
 * Proves supported shared state is Redis-authoritative under write A→read B, write B→read A, fresh-client
 * reload, and namespace isolation. Does <strong>not</strong> claim multi-process, mixed-version,
 * rolling-deploy, Redis Cluster, or production HA correctness. Skipped when Docker CLI cannot start Redis.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DistributedMultiClientStateConsistencyValidationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TENANT = "consistency-tenant";
    private static final String REDIS_IMAGE = "redis:7-alpine";

    private static final AtomicReference<DockerRedis> REDIS = new AtomicReference<>();

    @BeforeAll
    static void startRedis() {
        DockerRedis redis = null;
        try {
            redis = DockerRedis.start();
            awaitRedisReady(redis, 20_000);
            REDIS.set(redis);
        } catch (Exception e) {
            if (redis != null) {
                try {
                    redis.close();
                } catch (Exception ignored) {
                    // Best-effort cleanup after a failed readiness probe.
                }
            }
            REDIS.set(null);
            assumeTrue(false, "Docker Redis unavailable (redis:7-alpine via docker CLI): " + e);
        }
    }

    @AfterAll
    static void stopRedis() throws Exception {
        DockerRedis redis = REDIS.getAndSet(null);
        if (redis != null) {
            redis.close();
        }
    }

    @Test
    @Order(1)
    void independentClients_doNotShareJavaConnectionFactories() {
        DockerRedis redis = REDIS.get();
        try (PeerRedis a = PeerRedis.connect(redis); PeerRedis b = PeerRedis.connect(redis)) {
            assertThat(a.factory).isNotSameAs(b.factory);
            assertThat(a.template).isNotSameAs(b.template);
            assertThat(System.identityHashCode(a.template)).isNotEqualTo(System.identityHashCode(b.template));
        }
    }

    @Test
    @Order(2)
    void quarantine_writeA_readB_and_writeB_readA_and_clearObserved_and_freshClient() throws Exception {
        SentinelProperties props = quarantineProps("aisentinel", false);
        String keyA = "qa-identity|/consistency/a-" + UUID.randomUUID();
        String keyB = "qb-identity|/consistency/b-" + UUID.randomUUID();
        long untilA = System.currentTimeMillis() + 600_000L;
        long untilB = System.currentTimeMillis() + 900_000L;
        DockerRedis redis = REDIS.get();

        try (PeerRedis peerA = PeerRedis.connect(redis); PeerRedis peerB = PeerRedis.connect(redis)) {
            RedisClusterQuarantineWriter writerA = newWriter(peerA.template, props);
            RedisClusterQuarantineWriter writerB = newWriter(peerB.template, props);
            RedisClusterQuarantineReader readerA = newReader(peerA.template, props);
            RedisClusterQuarantineReader readerB = newReader(peerB.template, props);
            try {
                writerA.publishQuarantine(TENANT, keyA, untilA);
                awaitKey(peerA.template, quarantineRedisKey(props, keyA), 5_000);
                OptionalLong seenByB = readerB.quarantineUntil(TENANT, keyA);
                assertThat(seenByB).isPresent();
                assertThat(seenByB.getAsLong()).isEqualTo(untilA);

                writerB.publishQuarantine(TENANT, keyB, untilB);
                awaitKey(peerB.template, quarantineRedisKey(props, keyB), 5_000);
                OptionalLong seenByA = readerA.quarantineUntil(TENANT, keyB);
                assertThat(seenByA).isPresent();
                assertThat(seenByA.getAsLong()).isEqualTo(untilB);

                Long ttlA = peerB.template.getExpire(quarantineRedisKey(props, keyA));
                Long ttlB = peerA.template.getExpire(quarantineRedisKey(props, keyA));
                assertThat(ttlA).isNotNull().isPositive();
                assertThat(ttlB).isNotNull().isPositive();

                writerB.clearQuarantine(TENANT, keyA);
                awaitKeyAbsent(peerB.template, quarantineRedisKey(props, keyA), 5_000);
                assertThat(readerA.quarantineUntil(TENANT, keyA)).isEmpty();
                assertThat(readerB.quarantineUntil(TENANT, keyA)).isEmpty();
            } finally {
                writerA.destroy();
                writerB.destroy();
                readerA.destroy();
                readerB.destroy();
            }
        }

        try (PeerRedis peerC = PeerRedis.connect(redis)) {
            RedisClusterQuarantineReader readerC = newReader(peerC.template, props);
            try {
                OptionalLong fromC = readerC.quarantineUntil(TENANT, keyB);
                assertThat(fromC).isPresent();
                assertThat(fromC.getAsLong()).isEqualTo(untilB);
            } finally {
                readerC.destroy();
            }
        }
    }

    @Test
    @Order(3)
    void quarantine_differentKeyPrefix_isIsolated() throws Exception {
        SentinelProperties shared = quarantineProps("aisentinel", false);
        SentinelProperties other = quarantineProps("otherpfx", false);
        String enforcementKey = "iso-id|/iso/" + UUID.randomUUID();
        long until = System.currentTimeMillis() + 600_000L;
        DockerRedis redis = REDIS.get();

        try (PeerRedis peerA = PeerRedis.connect(redis); PeerRedis peerB = PeerRedis.connect(redis)) {
            RedisClusterQuarantineWriter writerA = newWriter(peerA.template, shared);
            RedisClusterQuarantineReader readerSame = newReader(peerB.template, shared);
            RedisClusterQuarantineReader readerOther = newReader(peerB.template, other);
            try {
                writerA.publishQuarantine(TENANT, enforcementKey, until);
                awaitKey(peerA.template, quarantineRedisKey(shared, enforcementKey), 5_000);
                assertThat(readerSame.quarantineUntil(TENANT, enforcementKey)).isPresent();
                assertThat(readerOther.quarantineUntil(TENANT, enforcementKey)).isEmpty();
                assertThat(peerB.template.hasKey(quarantineRedisKey(other, enforcementKey))).isFalse();
            } finally {
                writerA.destroy();
                readerSame.destroy();
                readerOther.destroy();
            }
        }
    }

    @Test
    @Order(4)
    void trustBaseline_dualClient_writeA_readB_writeB_readA_rawJsonConverges_freshClient() throws Exception {
        SentinelProperties props = trustProps("aisentinel:trust:bl:");
        String logical = "p:consistency-" + UUID.randomUUID();
        String redisKey = trustRedisKey(
            props.getIdentity().getTrust().getDistributed().getKeyPrefix(), logical);
        DockerRedis redis = REDIS.get();

        try (PeerRedis peerA = PeerRedis.connect(redis); PeerRedis peerB = PeerRedis.connect(redis)) {
            RedisFailOpenBehavioralBaselineStore storeA = newTrustStore(peerA.template, props);
            RedisFailOpenBehavioralBaselineStore storeB = newTrustStore(peerB.template, props);
            try {
                assertThat(storeA.updateAndGetPrevious(logical, "/e1", 11L, 1, 1_000L)).isNull();
                assertThat(storeA.updateAndGetPrevious(logical, "/e2", 22L, 2, 1_001L)).isNotNull()
                    .extracting(e -> e.observationCount).isEqualTo(1L);

                BehavioralBaselineEntry fromB = storeB.updateAndGetPrevious(logical, "/e3", 33L, 3, 1_002L);
                assertThat(fromB).isNotNull();
                assertThat(fromB.observationCount).isEqualTo(2L);
                assertThat(fromB.lastEndpoint).isEqualTo("/e2");

                BehavioralBaselineEntry fromA = storeA.updateAndGetPrevious(logical, "/e4", 44L, 4, 1_003L);
                assertThat(fromA).isNotNull();
                assertThat(fromA.observationCount).isEqualTo(3L);
                assertThat(fromA.lastEndpoint).isEqualTo("/e3");

                String rawFromA = peerA.template.opsForValue().get(redisKey);
                String rawFromB = peerB.template.opsForValue().get(redisKey);
                assertThat(rawFromA).isNotBlank();
                assertThat(rawFromB).isEqualTo(rawFromA);
                JsonNode node = JSON.readTree(rawFromA);
                assertThat(node.get("observationCount").asLong()).isEqualTo(4L);
                assertThat(node.get("lastEndpoint").asText()).isEqualTo("/e4");

                Long ttlA = peerA.template.getExpire(redisKey, TimeUnit.MILLISECONDS);
                Long ttlB = peerB.template.getExpire(redisKey, TimeUnit.MILLISECONDS);
                assertThat(ttlA).isNotNull().isPositive();
                assertThat(ttlB).isNotNull().isPositive();
            } finally {
                storeA.destroy();
                storeB.destroy();
            }
        }

        try (PeerRedis peerC = PeerRedis.connect(redis)) {
            RedisFailOpenBehavioralBaselineStore storeC = newTrustStore(peerC.template, props);
            try {
                BehavioralBaselineEntry prev = storeC.updateAndGetPrevious(logical, "/e5", 55L, 5, 1_004L);
                assertThat(prev).isNotNull();
                assertThat(prev.observationCount).isEqualTo(4L);
            } finally {
                storeC.destroy();
            }
        }
    }

    @Test
    @Order(5)
    void trustBaseline_differentKeyPrefix_isIsolated() throws Exception {
        SentinelProperties pA = trustProps("aisentinel:trust:bl:");
        SentinelProperties pB = trustProps("other:trust:bl:");
        String logical = "p:iso-" + UUID.randomUUID();
        DockerRedis redis = REDIS.get();

        try (PeerRedis peerA = PeerRedis.connect(redis); PeerRedis peerB = PeerRedis.connect(redis)) {
            RedisFailOpenBehavioralBaselineStore storeA = newTrustStore(peerA.template, pA);
            RedisFailOpenBehavioralBaselineStore storeB = newTrustStore(peerB.template, pB);
            try {
                assertThat(storeA.updateAndGetPrevious(logical, "/a", 1L, 1, 10L)).isNull();
                assertThat(storeB.updateAndGetPrevious(logical, "/b", 2L, 2, 11L)).isNull();
                String keyA = trustRedisKey(pA.getIdentity().getTrust().getDistributed().getKeyPrefix(), logical);
                String keyB = trustRedisKey(pB.getIdentity().getTrust().getDistributed().getKeyPrefix(), logical);
                assertThat(keyA).isNotEqualTo(keyB);
                assertThat(peerA.template.hasKey(keyA)).isTrue();
                assertThat(peerB.template.hasKey(keyB)).isTrue();
                assertThat(peerA.template.opsForValue().get(keyA))
                    .isNotEqualTo(peerB.template.opsForValue().get(keyB));
            } finally {
                storeA.destroy();
                storeB.destroy();
            }
        }
    }

    @Test
    @Order(6)
    void trustBaseline_concurrentDualClientUpdates_preserveObservationCount() throws Exception {
        SentinelProperties props = trustProps("aisentinel:trust:bl:");
        String logical = "p:conc-" + UUID.randomUUID();
        String redisKey = trustRedisKey(
            props.getIdentity().getTrust().getDistributed().getKeyPrefix(), logical);
        int perClient = 20;
        DockerRedis redis = REDIS.get();

        try (PeerRedis peerA = PeerRedis.connect(redis); PeerRedis peerB = PeerRedis.connect(redis)) {
            RedisFailOpenBehavioralBaselineStore storeA = newTrustStore(peerA.template, props);
            RedisFailOpenBehavioralBaselineStore storeB = newTrustStore(peerB.template, props);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger errors = new AtomicInteger();
                Future<?> fa = pool.submit(() -> {
                    awaitStart(start);
                    for (int i = 0; i < perClient; i++) {
                        try {
                            storeA.updateAndGetPrevious(logical, "/a", i, 1, 1000L + i);
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                });
                Future<?> fb = pool.submit(() -> {
                    awaitStart(start);
                    for (int i = 0; i < perClient; i++) {
                        try {
                            storeB.updateAndGetPrevious(logical, "/b", i, 2, 2000L + i);
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                });
                start.countDown();
                fa.get(30, TimeUnit.SECONDS);
                fb.get(30, TimeUnit.SECONDS);
                assertThat(errors.get()).isZero();

                String raw = peerA.template.opsForValue().get(redisKey);
                assertThat(raw).isNotBlank();
                assertThat(JSON.readTree(raw).get("observationCount").asLong()).isEqualTo(perClient * 2L);
                assertThat(peerB.template.opsForValue().get(redisKey)).isEqualTo(raw);
            } finally {
                pool.shutdownNow();
                storeA.destroy();
                storeB.destroy();
            }
        }
    }

    @Test
    @Order(7)
    void throttle_dualClient_sharedCounter_and_rejectPath() throws Exception {
        SentinelProperties props = throttleProps();
        String enforcementKey = "th-id|/th/" + UUID.randomUUID();
        int max = props.getDistributed().getClusterThrottleMaxRequestsPerWindow();
        DockerRedis redis = REDIS.get();

        try (PeerRedis peerA = PeerRedis.connect(redis); PeerRedis peerB = PeerRedis.connect(redis)) {
            RedisClusterThrottleStore storeA = newThrottle(peerA.template, props);
            RedisClusterThrottleStore storeB = newThrottle(peerB.template, props);
            try {
                assertThat(storeA.tryAcquire(TENANT, enforcementKey)).isTrue();
                assertThat(storeB.tryAcquire(TENANT, enforcementKey)).isTrue();

                for (int i = 2; i < max; i++) {
                    boolean ok = (i % 2 == 0)
                        ? storeA.tryAcquire(TENANT, enforcementKey)
                        : storeB.tryAcquire(TENANT, enforcementKey);
                    assertThat(ok).isTrue();
                }
                assertThat(storeA.tryAcquire(TENANT, enforcementKey)).isFalse();
                assertThat(storeB.tryAcquire(TENANT, enforcementKey)).isFalse();

                long bucketId = System.currentTimeMillis()
                    / Math.max(1L, props.getDistributed().getClusterThrottleWindow().toMillis());
                String redisKey = DistributedThrottleKeyBuilder.redisKey(
                    props.getDistributed().getRedis().getKeyPrefix(), TENANT, bucketId, enforcementKey);
                String rawA = peerA.template.opsForValue().get(redisKey);
                String rawB = peerB.template.opsForValue().get(redisKey);
                // Lua INCR runs before allow/reject; two over-limit acquires leave counter at max+2.
                assertThat(rawA).isEqualTo(String.valueOf(max + 2));
                assertThat(rawB).isEqualTo(rawA);
            } finally {
                storeA.destroy();
                storeB.destroy();
            }
        }
    }

    @Test
    @Order(8)
    void reconnectPeer_rereadsSharedQuarantineWithoutDivergence() throws Exception {
        SentinelProperties props = quarantineProps("aisentinel", false);
        String enforcementKey = "reconnect-id|/r/" + UUID.randomUUID();
        long until = System.currentTimeMillis() + 600_000L;
        DockerRedis redis = REDIS.get();

        try (PeerRedis peerA = PeerRedis.connect(redis)) {
            RedisClusterQuarantineWriter writerA = newWriter(peerA.template, props);
            try {
                writerA.publishQuarantine(TENANT, enforcementKey, until);
                awaitKey(peerA.template, quarantineRedisKey(props, enforcementKey), 5_000);
            } finally {
                writerA.destroy();
            }
        }

        try (PeerRedis peerB1 = PeerRedis.connect(redis)) {
            RedisClusterQuarantineReader readerB1 = newReader(peerB1.template, props);
            try {
                OptionalLong first = readerB1.quarantineUntil(TENANT, enforcementKey);
                assertThat(first).isPresent();
                assertThat(first.getAsLong()).isEqualTo(until);
            } finally {
                readerB1.destroy();
            }
        }
        try (PeerRedis peerB2 = PeerRedis.connect(redis)) {
            RedisClusterQuarantineReader readerB2 = newReader(peerB2.template, props);
            try {
                OptionalLong second = readerB2.quarantineUntil(TENANT, enforcementKey);
                assertThat(second).isPresent();
                assertThat(second.getAsLong()).isEqualTo(until);
                assertThat(peerB2.template.opsForValue().get(quarantineRedisKey(props, enforcementKey)))
                    .isEqualTo(Long.toString(until));
            } finally {
                readerB2.destroy();
            }
        }
    }

    private static SentinelProperties quarantineProps(String keyPrefix, boolean cacheEnabled) {
        SentinelProperties p = new SentinelProperties();
        p.getDistributed().setEnabled(true);
        p.getDistributed().setTenantId(TENANT);
        p.getDistributed().getRedis().setEnabled(true);
        p.getDistributed().getRedis().setKeyPrefix(keyPrefix);
        p.getDistributed().getRedis().setLookupTimeout(Duration.ofSeconds(2));
        p.getDistributed().getCache().setEnabled(cacheEnabled);
        return p;
    }

    private static SentinelProperties trustProps(String keyPrefix) {
        SentinelProperties p = new SentinelProperties();
        p.getIdentity().getTrust().setBaselineTtl(Duration.ofMinutes(15));
        p.getIdentity().getTrust().getDistributed().setEnabled(true);
        p.getIdentity().getTrust().getDistributed().setKeyPrefix(keyPrefix);
        p.getIdentity().getTrust().getDistributed().setCommandTimeout(Duration.ofSeconds(3));
        return p;
    }

    private static SentinelProperties throttleProps() {
        SentinelProperties p = new SentinelProperties();
        p.getDistributed().setEnabled(true);
        p.getDistributed().setClusterThrottleEnabled(true);
        p.getDistributed().setClusterThrottleWindow(Duration.ofSeconds(30));
        p.getDistributed().setClusterThrottleMaxRequestsPerWindow(5);
        p.getDistributed().setClusterThrottleTimeout(Duration.ofSeconds(2));
        p.getDistributed().getRedis().setEnabled(true);
        p.getDistributed().getRedis().setKeyPrefix("aisentinel");
        return p;
    }

    private static RedisClusterQuarantineWriter newWriter(StringRedisTemplate tpl, SentinelProperties props) {
        return new RedisClusterQuarantineWriter(tpl, props, SentinelMetrics.NOOP, new DistributedQuarantineStatus());
    }

    private static RedisClusterQuarantineReader newReader(StringRedisTemplate tpl, SentinelProperties props) {
        return new RedisClusterQuarantineReader(tpl, props, SentinelMetrics.NOOP, new DistributedQuarantineStatus());
    }

    private static RedisFailOpenBehavioralBaselineStore newTrustStore(StringRedisTemplate tpl, SentinelProperties props) {
        IdentityBehavioralBaselineStore fallback =
            new IdentityBehavioralBaselineStore(Duration.ofHours(1), 10_000);
        return new RedisFailOpenBehavioralBaselineStore(tpl, fallback, props, SentinelMetrics.NOOP);
    }

    private static RedisClusterThrottleStore newThrottle(StringRedisTemplate tpl, SentinelProperties props) {
        return new RedisClusterThrottleStore(tpl, props, SentinelMetrics.NOOP, new DistributedThrottleStatus());
    }

    private static String quarantineRedisKey(SentinelProperties props, String enforcementKey) {
        return DistributedQuarantineKeyBuilder.redisKey(
            props.getDistributed().getRedis().getKeyPrefix(), TENANT, enforcementKey);
    }

    /** Same encoding as package-private trust Redis keys. */
    private static String trustRedisKey(String prefix, String logicalKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(logicalKey.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
    }

    private static void awaitKey(StringRedisTemplate tpl, String redisKey, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(tpl.hasKey(redisKey))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Redis key not found within timeout: " + redisKey);
    }

    private static void awaitKeyAbsent(StringRedisTemplate tpl, String redisKey, long timeoutMs)
        throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!Boolean.TRUE.equals(tpl.hasKey(redisKey))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Redis key still present within timeout: " + redisKey);
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("start latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void awaitRedisReady(DockerRedis redis, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        Exception last = null;
        while (System.currentTimeMillis() < deadline) {
            try (PeerRedis probe = PeerRedis.connect(redis)) {
                var connection = probe.template.getConnectionFactory().getConnection();
                try {
                    String pong = connection.ping();
                    if ("PONG".equalsIgnoreCase(pong)) {
                        return;
                    }
                } finally {
                    connection.close();
                }
            } catch (Exception e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Redis not ready within timeout", last);
    }

    /**
     * Ephemeral {@code redis:7-alpine} via Docker CLI (same image as Testcontainers / RedisContainerSupport).
     */
    private static final class DockerRedis implements AutoCloseable {
        private final String containerName;
        private final int hostPort;

        private DockerRedis(String containerName, int hostPort) {
            this.containerName = containerName;
            this.hostPort = hostPort;
        }

        static DockerRedis start() throws IOException, InterruptedException {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                port = socket.getLocalPort();
            }
            String name = "aisentinel-redis-consistency-" + UUID.randomUUID();
            run(
                "docker", "run", "-d", "--rm",
                "--name", name,
                "-p", "127.0.0.1:" + port + ":6379",
                REDIS_IMAGE);
            return new DockerRedis(name, port);
        }

        String host() {
            return "127.0.0.1";
        }

        int port() {
            return hostPort;
        }

        @Override
        public void close() throws IOException, InterruptedException {
            run("docker", "rm", "-f", containerName);
        }

        private static String run(String... command) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + (a.isEmpty() ? "" : "\n") + b).trim();
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("Command failed (" + String.join(" ", command) + "): " + output);
            }
            return output;
        }
    }

    /** Independent Lettuce connection to the shared Redis (peer stand-in; not another OS process). */
    private static final class PeerRedis implements AutoCloseable {
        final LettuceConnectionFactory factory;
        final StringRedisTemplate template;

        private PeerRedis(LettuceConnectionFactory factory, StringRedisTemplate template) {
            this.factory = factory;
            this.template = template;
        }

        static PeerRedis connect(DockerRedis redis) {
            RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration();
            cfg.setHostName(redis.host());
            cfg.setPort(redis.port());
            LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3))
                .build();
            LettuceConnectionFactory f = new LettuceConnectionFactory(cfg, client);
            f.afterPropertiesSet();
            StringRedisTemplate tpl = new StringRedisTemplate();
            tpl.setConnectionFactory(f);
            tpl.afterPropertiesSet();
            return new PeerRedis(f, tpl);
        }

        @Override
        public void close() {
            factory.destroy();
        }
    }
}
