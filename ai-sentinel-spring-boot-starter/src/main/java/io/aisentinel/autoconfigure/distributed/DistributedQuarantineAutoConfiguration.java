package io.aisentinel.autoconfigure.distributed;

import io.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader;
import io.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Registers {@link RedisClusterQuarantineReader} when Redis and Sentinel distributed flags are on.
 */
@AutoConfiguration(
    before = SentinelAutoConfiguration.class,
    after = org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class
)
@ConditionalOnClass(StringRedisTemplate.class)
@ConditionalOnBean(StringRedisTemplate.class)
@Conditional(OnDistributedRedisQuarantineEnabledCondition.class)
public class DistributedQuarantineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ClusterQuarantineReader.class)
    public ClusterQuarantineReader redisClusterQuarantineReader(StringRedisTemplate stringRedisTemplate,
                                                                 SentinelProperties sentinelProperties,
                                                                 SentinelMetrics sentinelMetrics,
                                                                 DistributedQuarantineStatus distributedQuarantineStatus) {
        return new RedisClusterQuarantineReader(stringRedisTemplate, sentinelProperties, sentinelMetrics,
            distributedQuarantineStatus);
    }
}
