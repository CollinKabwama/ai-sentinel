package io.aisentinel.validation;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineKeyBuilder;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineWriter;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.core.enforcement.EnforcementScope;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5.3 — In-flight cap 1 with a blocking Redis SET: second cluster publish is dropped; both identities remain
 * locally quarantined; only the first key is written (peers never observe the second in Redis). Complements
 * {@link io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineWriterTest#secondPublishDroppedWhileFirstWriteBlocksRedis()}.
 */
class DistributedQuarantineDroppedWriteCompositeTest {

    @Test
    void secondQuarantineDroppedWhileFirstRedisSetBlocks_bothEnforcedLocally_onlyFirstRedisKey() throws Exception {
        SentinelProperties props = new SentinelProperties();
        props.getDistributed().getRedis().setKeyPrefix("pfx");
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
        var telemetry = mock(TelemetryEmitter.class);
        var composite = new CompositeEnforcementHandler(
            429, 60_000L, 5.0, telemetry, 100, 60_000L,
            EnforcementScope.IDENTITY_ENDPOINT,
            writer,
            "tenant-a");

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(java.io.OutputStream.nullOutputStream(), true));

        try {
            assertThat(composite.apply(EnforcementAction.QUARANTINE, request, response, "id1", "/a")).isFalse();
            assertThat(composite.apply(EnforcementAction.QUARANTINE, request, response, "id2", "/b")).isFalse();

            assertThat(composite.isQuarantined("id1", "/a")).isTrue();
            assertThat(composite.isQuarantined("id2", "/b")).isTrue();
            assertThat(metrics.dropped.get()).isEqualTo(1);
            assertThat(metrics.attempts.get()).isEqualTo(2);

            String key1 = DistributedQuarantineKeyBuilder.redisKey("pfx", "tenant-a", "id1|/a");
            String key2 = DistributedQuarantineKeyBuilder.redisKey("pfx", "tenant-a", "id2|/b");

            redisMayProceed.countDown();
            verify(ops, timeout(5_000).times(1)).set(eq(key1), anyString(), any(Duration.class));
            verify(ops, never()).set(eq(key2), anyString(), any(Duration.class));
        } finally {
            writer.destroy();
        }
    }

    @Test
    void publishPathReturnsQuicklyWhileRedisSetBlocks() throws Exception {
        SentinelProperties props = new SentinelProperties();
        props.getDistributed().getRedis().setMaxInFlightQuarantineWrites(4);
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
            var telemetry = mock(TelemetryEmitter.class);
            var composite = new CompositeEnforcementHandler(
                429, 60_000L, 5.0, telemetry, 100, 60_000L,
                EnforcementScope.IDENTITY_ENDPOINT,
                writer,
                "t");
            HttpServletRequest request = mock(HttpServletRequest.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(response.getWriter()).thenReturn(new PrintWriter(java.io.OutputStream.nullOutputStream(), true));
            assertThat(composite.apply(EnforcementAction.QUARANTINE, request, response, "z", "/z")).isFalse();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(elapsedMs).isLessThan(500L);
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
        final AtomicInteger dropped = new AtomicInteger();

        @Override
        public void recordDistributedQuarantineWriteAttempt() {
            attempts.incrementAndGet();
        }

        @Override
        public void recordDistributedQuarantineWriteDropped() {
            dropped.incrementAndGet();
        }
    }
}
