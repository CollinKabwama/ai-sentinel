package dev.aisentinel.autoconfigure.distributed;

import dev.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader;
import dev.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineWriter;
import dev.aisentinel.autoconfigure.distributed.throttle.RedisClusterThrottleStore;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import dev.aisentinel.distributed.throttle.ClusterThrottleStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers Redis-backed {@link ClusterQuarantineReader}, {@link ClusterQuarantineWriter}, and
 * {@link ClusterThrottleStore} when flags match.
 */
@AutoConfiguration(
    before = SentinelAutoConfiguration.class,
    after = org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class
)
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnBean(StringRedisTemplate.class)
@Conditional(OnDistributedRedisQuarantineClientEnabledCondition.class)
public class DistributedQuarantineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ClusterQuarantineReader.class)
    @Conditional(OnDistributedRedisQuarantineEnabledCondition.class)
    public ClusterQuarantineReader redisClusterQuarantineReader(StringRedisTemplate stringRedisTemplate,
                                                                 SentinelProperties sentinelProperties,
                                                                 SentinelMetrics sentinelMetrics,
                                                                 DistributedQuarantineStatus distributedQuarantineStatus) {
        return new RedisClusterQuarantineReader(stringRedisTemplate, sentinelProperties, sentinelMetrics,
            distributedQuarantineStatus);
    }

    @Bean
    @ConditionalOnMissingBean(ClusterQuarantineWriter.class)
    @Conditional(OnDistributedRedisQuarantineWriteEnabledCondition.class)
    public ClusterQuarantineWriter redisClusterQuarantineWriter(StringRedisTemplate stringRedisTemplate,
                                                                  SentinelProperties sentinelProperties,
                                                                  SentinelMetrics sentinelMetrics,
                                                                  DistributedQuarantineStatus distributedQuarantineStatus) {
        return new RedisClusterQuarantineWriter(stringRedisTemplate, sentinelProperties, sentinelMetrics,
            distributedQuarantineStatus);
    }

    @Bean
    @ConditionalOnMissingBean(ClusterThrottleStore.class)
    @Conditional(OnDistributedClusterThrottleEnabledCondition.class)
    public ClusterThrottleStore redisClusterThrottleStore(StringRedisTemplate stringRedisTemplate,
                                                          SentinelProperties sentinelProperties,
                                                          SentinelMetrics sentinelMetrics,
                                                          DistributedThrottleStatus distributedThrottleStatus) {
        return new RedisClusterThrottleStore(stringRedisTemplate, sentinelProperties, sentinelMetrics,
            distributedThrottleStatus);
    }
}
