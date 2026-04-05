package io.aisentinel.autoconfigure.distributed;

import io.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Owns {@link DistributedThrottleStatus} when cluster throttle is enabled.
 */
@AutoConfiguration(before = {DistributedQuarantineAutoConfiguration.class, SentinelAutoConfiguration.class})
@Conditional(OnDistributedThrottleStatusNeededCondition.class)
public class DistributedThrottleStatusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DistributedThrottleStatus.class)
    @ConditionalOnProperty(name = "ai.sentinel.enabled", havingValue = "true", matchIfMissing = true)
    public DistributedThrottleStatus distributedThrottleStatus() {
        return new DistributedThrottleStatus();
    }
}
