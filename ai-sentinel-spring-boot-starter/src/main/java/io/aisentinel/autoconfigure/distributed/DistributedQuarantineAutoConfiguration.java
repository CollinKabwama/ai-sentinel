package io.aisentinel.autoconfigure.distributed;

import io.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader;
import io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineWriter;
import io.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import io.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers Redis-backed {@link ClusterQuarantineReader} and {@link ClusterQuarantineWriter} when flags match.
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
}
