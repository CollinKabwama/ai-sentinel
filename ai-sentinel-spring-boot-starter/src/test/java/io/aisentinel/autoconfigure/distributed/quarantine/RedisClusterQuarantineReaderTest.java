package io.aisentinel.autoconfigure.distributed.quarantine;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.core.metrics.SentinelMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisClusterQuarantineReaderTest {

    private SentinelProperties props;
    private CountingMetrics metrics;

    @BeforeEach
    void setUp() {
        props = new SentinelProperties();
        props.getDistributed().setEnabled(true);
        props.getDistributed().setClusterQuarantineReadEnabled(true);
        props.getDistributed().getRedis().setEnabled(true);
        props.getDistributed().getRedis().setLookupTimeout(Duration.ofMillis(50));
        props.getDistributed().getCache().setTtl(Duration.ofMillis(500));
        props.getDistributed().getCache().setMaxEntries(100);
        metrics = new CountingMetrics();
    }

    @Test
    void readsUntilFromRedisAndCaches() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        long future = System.currentTimeMillis() + 120_000;
        when(ops.get(anyString())).thenReturn(Long.toString(future));
        StringRedisTemplate template = stubTemplate(ops);

        var status = new DistributedQuarantineStatus();
        var reader = new RedisClusterQuarantineReader(template, props, metrics, status);
        try {
            assertThat(reader.quarantineUntil("t1", "id|/x").getAsLong()).isEqualTo(future);
            assertThat(reader.quarantineUntil("t1", "id|/x").getAsLong()).isEqualTo(future);
            assertThat(metrics.lookups.get()).isEqualTo(2);
            assertThat(metrics.cacheMisses.get()).isEqualTo(1);
            assertThat(metrics.cacheHits.get()).isEqualTo(1);
            assertThat(metrics.clusterHits.get()).isGreaterThanOrEqualTo(2);
        } finally {
            reader.destroy();
        }
    }

    @Test
    void failOpenOnRedisException() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.get(anyString())).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        StringRedisTemplate template = stubTemplate(ops);

        var status = new DistributedQuarantineStatus();
        var reader = new RedisClusterQuarantineReader(template, props, metrics, status);
        try {
            assertThat(reader.quarantineUntil("t", "k")).isEmpty();
            assertThat(metrics.failures.get()).isEqualTo(1);
            assertThat(status.isRedisReaderDegraded()).isTrue();
        } finally {
            reader.destroy();
        }
    }

    @Test
    void failOpenOnTimeout() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.get(anyString())).thenAnswer(inv -> {
            Thread.sleep(500);
            return "1";
        });
        props.getDistributed().getRedis().setLookupTimeout(Duration.ofMillis(5));

        var status = new DistributedQuarantineStatus();
        var reader = new RedisClusterQuarantineReader(stubTemplate(ops), props, metrics, status);
        try {
            assertThat(reader.quarantineUntil("t", "k")).isEmpty();
            assertThat(metrics.timeouts.get()).isEqualTo(1);
            assertThat(status.isRedisReaderDegraded()).isTrue();
        } finally {
            reader.destroy();
        }
    }

    @Test
    void expiredUntilFromRedisReturnsEmpty() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        long past = System.currentTimeMillis() - 120_000;
        when(ops.get(anyString())).thenReturn(Long.toString(past));
        var reader = new RedisClusterQuarantineReader(stubTemplate(ops), props, metrics, new DistributedQuarantineStatus());
        try {
            assertThat(reader.quarantineUntil("t", "k")).isEmpty();
        } finally {
            reader.destroy();
        }
    }

    @Test
    void cacheDisabledHitsRedisEachTime() {
        props.getDistributed().getCache().setEnabled(false);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        long future = System.currentTimeMillis() + 120_000;
        when(ops.get(anyString())).thenReturn(Long.toString(future));
        var reader = new RedisClusterQuarantineReader(stubTemplate(ops), props, metrics, new DistributedQuarantineStatus());
        try {
            reader.quarantineUntil("t", "k");
            reader.quarantineUntil("t", "k");
            assertThat(metrics.cacheMisses.get()).isEqualTo(0);
            assertThat(metrics.cacheHits.get()).isEqualTo(0);
            assertThat(metrics.lookups.get()).isEqualTo(2);
        } finally {
            reader.destroy();
        }
    }

    @Test
    void unparsableRedisValueFailOpen() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(ops.get(anyString())).thenReturn("not-a-long");
        var reader = new RedisClusterQuarantineReader(stubTemplate(ops), props, metrics, new DistributedQuarantineStatus());
        try {
            assertThat(reader.quarantineUntil("t", "k")).isEmpty();
            assertThat(metrics.failures.get()).isEqualTo(1);
        } finally {
            reader.destroy();
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
        final AtomicInteger lookups = new AtomicInteger();
        final AtomicInteger clusterHits = new AtomicInteger();
        final AtomicInteger cacheHits = new AtomicInteger();
        final AtomicInteger cacheMisses = new AtomicInteger();
        final AtomicInteger timeouts = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();

        @Override
        public void recordDistributedQuarantineLookup() {
            lookups.incrementAndGet();
        }

        @Override
        public void recordDistributedQuarantineClusterHit() {
            clusterHits.incrementAndGet();
        }

        @Override
        public void recordDistributedQuarantineCacheHit() {
            cacheHits.incrementAndGet();
        }

        @Override
        public void recordDistributedQuarantineCacheMiss() {
            cacheMisses.incrementAndGet();
        }

        @Override
        public void recordDistributedRedisTimeout() {
            timeouts.incrementAndGet();
        }

        @Override
        public void recordDistributedRedisFailure() {
            failures.incrementAndGet();
        }

        @Override
        public void recordDistributedRedisLookupDurationNanos(long nanos) {
        }
    }
}
