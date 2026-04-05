package io.aisentinel.autoconfigure.distributed.quarantine;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineKeyBuilder;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.core.metrics.SentinelMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class RedisClusterQuarantineWriterTest {

    @Test
    void setsKeyWithTtlMatchingRemainingQuarantine() throws Exception {
        SentinelProperties props = new SentinelProperties();
        props.getDistributed().setEnabled(true);
        props.getDistributed().getRedis().setKeyPrefix("pfx");
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate template = stubTemplate(ops);
        var status = new DistributedQuarantineStatus();
        var metrics = new CountingMetrics();
        var writer = new RedisClusterQuarantineWriter(template, props, metrics, status);
        try {
            long until = System.currentTimeMillis() + 120_000;
            writer.publishQuarantine("mytenant", "id|/p", until);
            String expectedKey = DistributedQuarantineKeyBuilder.redisKey("pfx", "mytenant", "id|/p");
            verify(ops, timeout(2_000).times(1)).set(eq(expectedKey), eq(Long.toString(until)), any(Duration.class));
            assertThat(metrics.attempts.get()).isEqualTo(1);
            assertThat(metrics.successes.get()).isEqualTo(1);
            assertThat(metrics.skippedExpired.get()).isZero();
            assertThat(status.isRedisWriterDegraded()).isFalse();
        } finally {
            writer.destroy();
        }
    }

    @Test
    void redisFailureMarksDegradedAndIncrementsFailure() throws Exception {
        SentinelProperties props = new SentinelProperties();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        doThrow(new RedisConnectionFailureException("down")).when(ops).set(anyString(), anyString(), any(Duration.class));
        StringRedisTemplate template = stubTemplate(ops);
        var status = new DistributedQuarantineStatus();
        var metrics = new CountingMetrics();
        var writer = new RedisClusterQuarantineWriter(template, props, metrics, status);
        try {
            writer.publishQuarantine("t", "k", System.currentTimeMillis() + 60_000);
            verify(ops, timeout(2_000).times(1)).set(anyString(), anyString(), any(Duration.class));
            Thread.sleep(300);
            assertThat(metrics.failures.get()).isEqualTo(1);
            assertThat(status.isRedisWriterDegraded()).isTrue();
        } finally {
            writer.destroy();
        }
    }

    @Test
    void skipsSetWhenAlreadyExpiredWithoutSuccessOrDegradedClear() throws Exception {
        SentinelProperties props = new SentinelProperties();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate template = stubTemplate(ops);
        var status = new DistributedQuarantineStatus();
        var metrics = new CountingMetrics();
        var writer = new RedisClusterQuarantineWriter(template, props, metrics, status);
        try {
            status.recordWriteError("prior", new RuntimeException("x"));
            writer.publishQuarantine("t", "k", System.currentTimeMillis() - 1);
            Thread.sleep(150);
            verify(ops, org.mockito.Mockito.never()).set(anyString(), anyString(), any(Duration.class));
            assertThat(metrics.successes.get()).isZero();
            assertThat(metrics.skippedExpired.get()).isEqualTo(1);
            assertThat(status.isRedisWriterDegraded()).isTrue();
        } finally {
            writer.destroy();
        }
    }

    @Test
    void expiredSkipAfterRedisFailureDoesNotClearDegraded() throws Exception {
        SentinelProperties props = new SentinelProperties();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        doThrow(new RedisConnectionFailureException("down")).when(ops).set(anyString(), anyString(), any(Duration.class));
        StringRedisTemplate template = stubTemplate(ops);
        var status = new DistributedQuarantineStatus();
        var metrics = new CountingMetrics();
        var writer = new RedisClusterQuarantineWriter(template, props, metrics, status);
        try {
            writer.publishQuarantine("t", "k", System.currentTimeMillis() + 60_000);
            verify(ops, timeout(2_000).times(1)).set(anyString(), anyString(), any(Duration.class));
            assertThat(status.isRedisWriterDegraded()).isTrue();
            assertThat(metrics.failures.get()).isEqualTo(1);

            writer.publishQuarantine("t", "k2", System.currentTimeMillis() - 1);
            Thread.sleep(150);
            assertThat(status.isRedisWriterDegraded()).isTrue();
            assertThat(metrics.successes.get()).isZero();
            assertThat(metrics.skippedExpired.get()).isEqualTo(1);
        } finally {
            writer.destroy();
        }
    }

    @Test
    void publishReturnsWithoutWaitingForBlockedRedisSet() throws Exception {
        SentinelProperties props = new SentinelProperties();
        CountDownLatch redisMayProceed = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        doAnswer(inv -> {
            redisMayProceed.await();
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        StringRedisTemplate template = stubTemplate(ops);
        var writer = new RedisClusterQuarantineWriter(template, props, SentinelMetrics.NOOP, new DistributedQuarantineStatus());
        try {
            long until = System.currentTimeMillis() + 120_000;
            long t0 = System.nanoTime();
            writer.publishQuarantine("t", "k", until);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(elapsedMs).isLessThan(200L);
            redisMayProceed.countDown();
            verify(ops, timeout(3_000).times(1)).set(anyString(), anyString(), any(Duration.class));
        } finally {
            writer.destroy();
        }
    }

    @Test
    void secondPublishDroppedWhileFirstWriteBlocksRedis() throws Exception {
        SentinelProperties props = new SentinelProperties();
        props.getDistributed().getRedis().setMaxInFlightQuarantineWrites(1);
        CountDownLatch redisMayProceed = new CountDownLatch(1);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        doAnswer(inv -> {
            redisMayProceed.await();
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        StringRedisTemplate template = stubTemplate(ops);
        var metrics = new CountingMetrics();
        var writer = new RedisClusterQuarantineWriter(template, props, metrics, new DistributedQuarantineStatus());
        try {
            long until = System.currentTimeMillis() + 120_000;
            writer.publishQuarantine("t", "k1", until);
            writer.publishQuarantine("t", "k2", until);
            assertThat(metrics.dropped.get()).isEqualTo(1);
            assertThat(metrics.attempts.get()).isEqualTo(2);
            redisMayProceed.countDown();
            verify(ops, timeout(3_000).times(1)).set(anyString(), anyString(), any(Duration.class));
        } finally {
            writer.destroy();
        }
    }

    private static StringRedisTemplate stubTemplate(ValueOperations<String, String> ops) {
        return new StringRedisTemplate() {
            @Override
            public void afterPropertiesSet() {
            }

            @Override
            public ValueOperations<String, String> opsForValue() {
                return ops;
            }
        };
    }

    private static final class CountingMetrics implements SentinelMetrics {
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicInteger skippedExpired = new AtomicInteger();
        final AtomicInteger dropped = new AtomicInteger();
        final AtomicInteger schedulerRejected = new AtomicInteger();

        @Override
        public void recordDistributedQuarantineWriteAttempt() {
            attempts.incrementAndGet();
        }

        @Override
        public void recordDistributedQuarantineWriteSuccess() {
            successes.incrementAndGet();
        }

        @Override
        public void recordDistributedQuarantineWriteFailure() {
            failures.incrementAndGet();
        }

        @Override
        public void recordDistributedQuarantineWriteSkippedExpired() {
            skippedExpired.incrementAndGet();
        }

        @Override
        public void recordDistributedQuarantineWriteDropped() {
            dropped.incrementAndGet();
        }

        @Override
        public void recordDistributedQuarantineWriteSchedulerRejected() {
            schedulerRejected.incrementAndGet();
        }
    }
}
