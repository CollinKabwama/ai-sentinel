package io.aisentinel.autoconfigure.identity.trust;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.identity.trust.BehavioralBaselineEntry;
import io.aisentinel.core.identity.trust.BehavioralBaselineMerge;
import io.aisentinel.core.identity.trust.IdentityBehavioralBaselineStore;
import io.aisentinel.core.metrics.SentinelMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RedisFailOpenBehavioralBaselineStoreTest {

    private static final ObjectMapper JSON =
        new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    void redisFailureFallsBackToLocalAndIncrementsMetrics() {
        StringRedisTemplate tpl = templateExecuteAlwaysThrows();

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        AtomicInteger fallback = new AtomicInteger();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordTrustBaselineRedisSuccess() {
                success.incrementAndGet();
            }

            @Override
            public void recordTrustBaselineRedisFailure() {
                failure.incrementAndGet();
            }

            @Override
            public void recordTrustBaselineRedisFallback() {
                fallback.incrementAndGet();
            }
        };

        IdentityBehavioralBaselineStore memory = new IdentityBehavioralBaselineStore(Duration.ofHours(1), 10_000);
        SentinelProperties props = propsDistributedOn();
        RedisFailOpenBehavioralBaselineStore store =
            new RedisFailOpenBehavioralBaselineStore(tpl, memory, props, metrics);

        BehavioralBaselineEntry first = store.updateAndGetPrevious("p:alice", "/a", 1L, 2, 1000L);
        assertThat(first).isNull();
        BehavioralBaselineEntry second = store.updateAndGetPrevious("p:alice", "/a", 1L, 2, 1001L);
        assertThat(second.observationCount).isEqualTo(1);

        assertThat(success.get()).isZero();
        assertThat(failure.get()).isEqualTo(2);
        assertThat(fallback.get()).isEqualTo(2);
        store.destroy();
    }

    @Test
    void redisWriteFailureAfterSuccessfulReadFallsBackAndIncrementsMetrics() throws Exception {
        ConcurrentHashMap<String, String> backing = new ConcurrentHashMap<>();
        SentinelProperties props = propsDistributedOn();
        String rkey = RedisFailOpenBehavioralBaselineStore.redisKey(
            props.getIdentity().getTrust().getDistributed().getKeyPrefix(), "p:alice");
        BehavioralBaselineEntry existing = new BehavioralBaselineEntry();
        existing.lastSeenMs = 10L;
        existing.observationCount = 1L;
        existing.lastEndpoint = "/old";
        existing.lastHeaderFingerprintHash = 9L;
        existing.lastIpBucket = 1;
        backing.put(rkey, JSON.writeValueAsString(existing));

        AtomicBoolean failWrite = new AtomicBoolean(true);
        StringRedisTemplate tpl = templateSimulatingRedis(backing, () -> {
            if (failWrite.get()) {
                throw new RedisConnectionFailureException("set failed");
            }
        });

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        AtomicInteger fallback = new AtomicInteger();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordTrustBaselineRedisSuccess() {
                success.incrementAndGet();
            }

            @Override
            public void recordTrustBaselineRedisFailure() {
                failure.incrementAndGet();
            }

            @Override
            public void recordTrustBaselineRedisFallback() {
                fallback.incrementAndGet();
            }
        };

        IdentityBehavioralBaselineStore memory = new IdentityBehavioralBaselineStore(Duration.ofHours(1), 10_000);
        RedisFailOpenBehavioralBaselineStore store =
            new RedisFailOpenBehavioralBaselineStore(tpl, memory, props, metrics);

        BehavioralBaselineEntry prev = store.updateAndGetPrevious("p:alice", "/new", 2L, 3, 100L);
        assertThat(prev).isNull();

        assertThat(success.get()).isZero();
        assertThat(failure.get()).isEqualTo(1);
        assertThat(fallback.get()).isEqualTo(1);

        BehavioralBaselineEntry memOnly = memory.updateAndGetPrevious("p:alice", "/x", 0L, 0, 200L);
        assertThat(memOnly.observationCount).isEqualTo(1);

        failWrite.set(false);
        BehavioralBaselineEntry prev2 = store.updateAndGetPrevious("p:alice", "/next", 2L, 3, 300L);
        assertThat(prev2).isNotNull();
        assertThat(prev2.observationCount).isEqualTo(1);
        assertThat(success.get()).isEqualTo(1);

        store.destroy();
    }

    @Test
    void commandTimeoutFallsBack() {
        SentinelProperties props = propsDistributedOn();
        props.getIdentity().getTrust().getDistributed().setCommandTimeout(Duration.ofMillis(25));
        StringRedisTemplate tpl = new StringRedisTemplate() {
            @Override
            public void afterPropertiesSet() {
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return (T) "";
            }
        };

        IdentityBehavioralBaselineStore memory = new IdentityBehavioralBaselineStore(Duration.ofHours(1), 10_000);
        AtomicInteger failure = new AtomicInteger();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordTrustBaselineRedisFailure() {
                failure.incrementAndGet();
            }
        };
        RedisFailOpenBehavioralBaselineStore store =
            new RedisFailOpenBehavioralBaselineStore(tpl, memory, props, metrics);
        store.updateAndGetPrevious("p:slow", "/a", 0L, 0, 1L);
        assertThat(failure.get()).isEqualTo(1);
        store.destroy();
    }

    @Test
    void twoStoresShareRedisBackingContinuity() throws Exception {
        ConcurrentHashMap<String, String> backing = new ConcurrentHashMap<>();
        StringRedisTemplate tpl = templateSimulatingRedis(backing, () -> {});

        SentinelProperties props = propsDistributedOn();
        IdentityBehavioralBaselineStore memA = new IdentityBehavioralBaselineStore(Duration.ofHours(1), 10_000);
        IdentityBehavioralBaselineStore memB = new IdentityBehavioralBaselineStore(Duration.ofHours(1), 10_000);
        RedisFailOpenBehavioralBaselineStore nodeA =
            new RedisFailOpenBehavioralBaselineStore(tpl, memA, props, SentinelMetrics.NOOP);
        RedisFailOpenBehavioralBaselineStore nodeB =
            new RedisFailOpenBehavioralBaselineStore(tpl, memB, props, SentinelMetrics.NOOP);

        assertThat(nodeA.updateAndGetPrevious("p:bob", "/x", 3L, 4, 10L)).isNull();
        BehavioralBaselineEntry prevB = nodeB.updateAndGetPrevious("p:bob", "/x", 3L, 4, 11L);
        assertThat(prevB).isNotNull();
        assertThat(prevB.observationCount).isEqualTo(1);
        nodeA.destroy();
        nodeB.destroy();
    }

    @Test
    void redisKeyIsStableHex() {
        String k = RedisFailOpenBehavioralBaselineStore.redisKey("pre:", "p:user");
        assertThat(k).startsWith("pre:").hasSize("pre:".length() + 64);
    }

    private static SentinelProperties propsDistributedOn() {
        SentinelProperties p = new SentinelProperties();
        p.getIdentity().getTrust().getDistributed().setEnabled(true);
        p.getIdentity().getTrust().getDistributed().setKeyPrefix("t:");
        return p;
    }

    private static StringRedisTemplate templateExecuteAlwaysThrows() {
        return new StringRedisTemplate() {
            @Override
            public void afterPropertiesSet() {
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new RedisConnectionFailureException("down");
            }
        };
    }

    /**
     * Simulates Lua read-merge-write: merge, then {@code afterMergeBeforePut} (throw = SET fails after successful read),
     * then store in {@code backing}.
     */
    private static StringRedisTemplate templateSimulatingRedis(ConcurrentHashMap<String, String> backing,
                                                               Runnable afterMergeBeforePut) {
        return new StringRedisTemplate() {
            @Override
            public void afterPropertiesSet() {
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                try {
                    String rkey = keys.get(0);
                    long nowMs = Long.parseLong((String) args[1]);
                    String endpoint = (String) args[2];
                    long fp = Long.parseLong((String) args[3]);
                    int ip = Integer.parseInt((String) args[4]);
                    String raw = backing.get(rkey);
                    BehavioralBaselineEntry before =
                        raw == null || raw.isBlank() ? null : JSON.readValue(raw, BehavioralBaselineEntry.class);
                    BehavioralBaselineEntry after =
                        BehavioralBaselineMerge.merge(before, endpoint, fp, ip, nowMs);
                    afterMergeBeforePut.run();
                    String prevOut = raw == null || raw.isBlank() ? "" : raw;
                    backing.put(rkey, JSON.writeValueAsString(after));
                    return (T) prevOut;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }
}
