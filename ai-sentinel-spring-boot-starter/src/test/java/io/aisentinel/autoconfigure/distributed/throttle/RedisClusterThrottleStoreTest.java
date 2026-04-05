package io.aisentinel.autoconfigure.distributed.throttle;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedThrottleStatus;
import io.aisentinel.core.metrics.SentinelMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RedisClusterThrottleStoreTest {

    private RedisClusterThrottleStore store;

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.destroy();
        }
    }

    @Test
    void countOverMaxRejects() {
        AtomicReference<String> seenKey = new AtomicReference<>();
        StringRedisTemplate tpl = template((script, keys, args) -> {
            seenKey.set(keys.get(0));
            return 100L;
        });
        SentinelProperties props = props();
        props.getDistributed().setClusterThrottleMaxRequestsPerWindow(5);
        store = new RedisClusterThrottleStore(tpl, props, SentinelMetrics.NOOP, new DistributedThrottleStatus());
        assertThat(store.tryAcquire("tenant-x", "id|/p")).isFalse();
        assertThat(seenKey.get()).contains("tenant-x").contains(":th:");
    }

    @Test
    void countWithinMaxAllows() {
        StringRedisTemplate tpl = template((script, keys, args) -> 3L);
        SentinelProperties props = props();
        props.getDistributed().setClusterThrottleMaxRequestsPerWindow(10);
        store = new RedisClusterThrottleStore(tpl, props, SentinelMetrics.NOOP, new DistributedThrottleStatus());
        assertThat(store.tryAcquire("t", "k|/z")).isTrue();
    }

    @Test
    void redisErrorFailOpenAllows() {
        StringRedisTemplate tpl = template((script, keys, args) -> {
            throw new RedisConnectionFailureException("down");
        });
        DistributedThrottleStatus status = new DistributedThrottleStatus();
        store = new RedisClusterThrottleStore(tpl, props(), SentinelMetrics.NOOP, status);
        assertThat(store.tryAcquire("t", "k")).isTrue();
        assertThat(status.isRedisThrottleDegraded()).isTrue();
        assertThat(status.getLastRedisErrorSummary()).startsWith("redis_failure");
    }

    /**
     * Simulates atomic Redis INCR: under concurrent load, exactly {@code max} calls see a count &le; max (allow)
     * and the rest see count &gt; max (cluster reject).
     */
    @Test
    void burstConcurrent_exactlyMaxAllowedRestRejected() throws Exception {
        int max = 5;
        int extra = 10;
        AtomicInteger counter = new AtomicInteger();
        StringRedisTemplate tpl = template((script, keys, args) -> (long) counter.incrementAndGet());
        SentinelProperties props = props();
        props.getDistributed().setClusterThrottleMaxRequestsPerWindow(max);
        props.getDistributed().setClusterThrottleMaxInFlight(256);
        store = new RedisClusterThrottleStore(tpl, props, SentinelMetrics.NOOP, new DistributedThrottleStatus());
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        try {
            int total = max + extra;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                futures.add(ex.submit(() -> {
                    start.await();
                    return store.tryAcquire("tenant", "same-key|/api");
                }));
            }
            start.countDown();
            int allows = 0;
            int rejects = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(30, TimeUnit.SECONDS)) {
                    allows++;
                } else {
                    rejects++;
                }
            }
            assertThat(allows).isEqualTo(max);
            assertThat(rejects).isEqualTo(extra);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    void redisBlocked_timesOut_failOpen_degradedAndTimeoutMetric() throws Exception {
        CountDownLatch redisBlock = new CountDownLatch(1);
        CountingSentinelMetrics metrics = new CountingSentinelMetrics();
        DistributedThrottleStatus status = new DistributedThrottleStatus();
        StringRedisTemplate tpl = template((s, k, a) -> {
            try {
                redisBlock.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return 1L;
        });
        SentinelProperties p = props();
        p.getDistributed().setClusterThrottleTimeout(Duration.ofMillis(50));
        p.getDistributed().getRedis().setLookupTimeout(Duration.ofSeconds(30));
        store = new RedisClusterThrottleStore(tpl, p, metrics, status);
        try {
            assertThat(store.tryAcquire("t", "k")).isTrue();
            assertThat(metrics.redisTimeout.get()).isEqualTo(1);
            assertThat(metrics.redisFailure.get()).isZero();
            assertThat(metrics.executorRejected.get()).isZero();
            assertThat(status.isRedisThrottleDegraded()).isTrue();
            assertThat(status.getLastRedisErrorSummary()).startsWith("redis_timeout");
        } finally {
            redisBlock.countDown();
            Thread.sleep(80);
        }
    }

    @Test
    void redisFailureThenSuccess_clearsDegraded() {
        AtomicInteger calls = new AtomicInteger();
        CountingSentinelMetrics metrics = new CountingSentinelMetrics();
        DistributedThrottleStatus status = new DistributedThrottleStatus();
        StringRedisTemplate tpl = template((s, k, a) -> {
            if (calls.getAndIncrement() == 0) {
                throw new RedisConnectionFailureException("down");
            }
            return 1L;
        });
        store = new RedisClusterThrottleStore(tpl, props(), metrics, status);
        assertThat(store.tryAcquire("t", "k")).isTrue();
        assertThat(status.isRedisThrottleDegraded()).isTrue();
        assertThat(status.getLastRedisErrorSummary()).startsWith("redis_failure");
        assertThat(metrics.redisFailure.get()).isEqualTo(1);
        assertThat(store.tryAcquire("t", "k")).isTrue();
        assertThat(status.isRedisThrottleDegraded()).isFalse();
    }

    @Test
    void inflightExhausted_failOpen_executorRejectedMetric() throws Exception {
        CountDownLatch redisEntered = new CountDownLatch(1);
        CountDownLatch unblockRedis = new CountDownLatch(1);
        CountingSentinelMetrics metrics = new CountingSentinelMetrics();
        DistributedThrottleStatus status = new DistributedThrottleStatus();
        StringRedisTemplate tpl = template((s, k, a) -> {
            redisEntered.countDown();
            try {
                assertThat(unblockRedis.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return 1L;
        });
        SentinelProperties p = props();
        p.getDistributed().setClusterThrottleMaxInFlight(1);
        store = new RedisClusterThrottleStore(tpl, p, metrics, status);
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Boolean> f1 = ex.submit(() -> store.tryAcquire("t", "k1"));
            assertThat(redisEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> f2 = ex.submit(() -> store.tryAcquire("t", "k2"));
            assertThat(f2.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(metrics.executorRejected.get()).isEqualTo(1);
            assertThat(metrics.redisTimeout.get()).isZero();
            assertThat(metrics.redisFailure.get()).isZero();
            assertThat(status.isRedisThrottleDegraded()).isTrue();
            assertThat(status.getLastRedisErrorSummary()).startsWith("inflight_exhausted");
            unblockRedis.countDown();
            assertThat(f1.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            ex.shutdownNow();
        }
    }

    private static SentinelProperties props() {
        SentinelProperties p = new SentinelProperties();
        p.getDistributed().setEnabled(true);
        p.getDistributed().getRedis().setKeyPrefix("aisentinel");
        p.getDistributed().getRedis().setLookupTimeout(Duration.ofMillis(500));
        p.getDistributed().setClusterThrottleWindow(Duration.ofSeconds(1));
        p.getDistributed().setClusterThrottleMaxRequestsPerWindow(30);
        p.getDistributed().setClusterThrottleMaxInFlight(1024);
        return p;
    }

    @FunctionalInterface
    private interface ExecuteFn {
        Long eval(RedisScript<Long> script, List<String> keys, Object[] args);
    }

    private static StringRedisTemplate template(ExecuteFn fn) {
        return new StringRedisTemplate() {
            @Override
            public void afterPropertiesSet() {
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                return (T) fn.eval((RedisScript<Long>) script, keys, args);
            }
        };
    }

    private static final class CountingSentinelMetrics implements SentinelMetrics {
        final AtomicLong redisTimeout = new AtomicLong();
        final AtomicLong redisFailure = new AtomicLong();
        final AtomicLong executorRejected = new AtomicLong();

        @Override
        public void recordDistributedThrottleRedisTimeout() {
            redisTimeout.incrementAndGet();
        }

        @Override
        public void recordDistributedThrottleRedisFailure() {
            redisFailure.incrementAndGet();
        }

        @Override
        public void recordDistributedThrottleExecutorRejected() {
            executorRejected.incrementAndGet();
        }
    }
}
