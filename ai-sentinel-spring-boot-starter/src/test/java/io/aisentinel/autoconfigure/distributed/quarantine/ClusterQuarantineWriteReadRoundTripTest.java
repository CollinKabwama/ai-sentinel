package io.aisentinel.autoconfigure.distributed.quarantine;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineKeyBuilder;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.core.metrics.SentinelMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared in-memory store simulating Redis: write path stores value; read path returns it (read-path unit style).
 */
class ClusterQuarantineWriteReadRoundTripTest {

    @Test
    void writeThenReadObservesSameUntil() throws Exception {
        ConcurrentHashMap<String, String> store = new ConcurrentHashMap<>();
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));

        StringRedisTemplate template = new StringRedisTemplate() {
            @Override
            public void afterPropertiesSet() {
            }

            @Override
            public ValueOperations<String, String> opsForValue() {
                return ops;
            }
        };

        SentinelProperties props = new SentinelProperties();
        props.getDistributed().setEnabled(true);
        props.getDistributed().setClusterQuarantineReadEnabled(true);
        props.getDistributed().getRedis().setEnabled(true);
        props.getDistributed().getRedis().setLookupTimeout(Duration.ofMillis(200));
        props.getDistributed().getCache().setEnabled(false);

        var status = new DistributedQuarantineStatus();
        var writer = new RedisClusterQuarantineWriter(template, props, SentinelMetrics.NOOP, status);
        var reader = new RedisClusterQuarantineReader(template, props, SentinelMetrics.NOOP, status);
        try {
            String tenant = "acme";
            String enforcementKey = "user1|/api/x";
            long until = System.currentTimeMillis() + 300_000;
            writer.publishQuarantine(tenant, enforcementKey, until);

            Thread.sleep(150);

            var opt = reader.quarantineUntil(tenant, enforcementKey);
            assertThat(opt).isPresent();
            assertThat(opt.getAsLong()).isEqualTo(until);

            String redisKey = DistributedQuarantineKeyBuilder.redisKey(
                props.getDistributed().getRedis().getKeyPrefix(), tenant, enforcementKey);
            assertThat(store).containsKey(redisKey);
        } finally {
            writer.destroy();
            reader.destroy();
        }
    }
}
