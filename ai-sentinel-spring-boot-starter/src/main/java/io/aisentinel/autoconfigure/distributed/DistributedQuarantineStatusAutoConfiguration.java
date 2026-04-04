package io.aisentinel.autoconfigure.distributed;

import io.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

/**
 * Owns the single {@link DistributedQuarantineStatus} bean when cluster quarantine read or write is enabled.
 * Loads before {@link DistributedQuarantineAutoConfiguration} so Redis beans can inject it.
 */
@AutoConfiguration(before = {DistributedQuarantineAutoConfiguration.class, SentinelAutoConfiguration.class})
@Conditional(OnDistributedQuarantineStatusNeededCondition.class)
public class DistributedQuarantineStatusAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DistributedQuarantineStatus.class)
    @ConditionalOnProperty(name = "ai.sentinel.enabled", havingValue = "true", matchIfMissing = true)
    public DistributedQuarantineStatus distributedQuarantineStatus() {
        return new DistributedQuarantineStatus();
    }
}
