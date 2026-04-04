package io.aisentinel.autoconfigure.actuator;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.autoconfigure.metrics.MicrometerSentinelMetrics;
import io.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.scoring.CompositeScorer;
import io.aisentinel.core.scoring.IsolationForestScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import io.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Registers the Sentinel actuator endpoint.
 * Loads after WebEndpointAutoConfiguration so the endpoint infrastructure is ready.
 */
@Slf4j
@AutoConfiguration(after = { WebEndpointAutoConfiguration.class, SentinelAutoConfiguration.class })
@ConditionalOnWebApplication
@ConditionalOnBean(CompositeEnforcementHandler.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
public class SentinelEndpointAutoConfiguration {

    @Bean
    @ConditionalOnBean(CompositeEnforcementHandler.class)
    public SentinelActuatorEndpoint sentinelActuatorEndpoint(SentinelProperties props,
                                                            CompositeEnforcementHandler enforcementHandlerImpl,
                                                            ObjectProvider<IsolationForestScorer> isolationForestScorerProvider,
                                                            ObjectProvider<StartupGrace> startupGraceProvider,
                                                            ObjectProvider<MicrometerSentinelMetrics> micrometerSentinelMetricsProvider,
                                                            ObjectProvider<CompositeScorer> compositeScorerProvider,
                                                            ObjectProvider<DistributedQuarantineStatus> distributedQuarantineStatusProvider,
                                                            ObjectProvider<ClusterQuarantineReader> clusterQuarantineReaderProvider) {
        log.debug("Registering Sentinel actuator endpoint");
        return new SentinelActuatorEndpoint(props, enforcementHandlerImpl, isolationForestScorerProvider.getIfAvailable(),
            startupGraceProvider.getIfAvailable(), micrometerSentinelMetricsProvider.getIfAvailable(),
            compositeScorerProvider.getIfAvailable(),
            distributedQuarantineStatusProvider.getIfAvailable(),
            clusterQuarantineReaderProvider.getIfAvailable());
    }
}
