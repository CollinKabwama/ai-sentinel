package dev.aisentinel.autoconfigure.actuator;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import dev.aisentinel.autoconfigure.distributed.DistributedThrottleStatus;
import dev.aisentinel.autoconfigure.distributed.training.TrainingPublishStatus;
import dev.aisentinel.distributed.training.TrainingCandidatePublisher;
import dev.aisentinel.autoconfigure.metrics.MicrometerSentinelMetrics;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import dev.aisentinel.distributed.throttle.ClusterThrottleStore;
import dev.aisentinel.core.decision.LastDecisionExplanation;
import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import dev.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
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
                                                            ObjectProvider<LastDecisionExplanation> lastDecisionExplanationProvider,
                                                            ObjectProvider<DistributedQuarantineStatus> distributedQuarantineStatusProvider,
                                                            ObjectProvider<DistributedThrottleStatus> distributedThrottleStatusProvider,
                                                            ObjectProvider<ClusterQuarantineReader> clusterQuarantineReaderProvider,
                                                            ObjectProvider<ClusterQuarantineWriter> clusterQuarantineWriterProvider,
                                                            ObjectProvider<ClusterThrottleStore> clusterThrottleStoreProvider,
                                                            ObjectProvider<TrainingPublishStatus> trainingPublishStatusProvider,
                                                            ObjectProvider<TrainingCandidatePublisher> trainingCandidatePublisherProvider) {
        log.debug("Registering Sentinel actuator endpoint");
        return new SentinelActuatorEndpoint(props, enforcementHandlerImpl, isolationForestScorerProvider.getIfAvailable(),
            startupGraceProvider.getIfAvailable(), micrometerSentinelMetricsProvider.getIfAvailable(),
            compositeScorerProvider.getIfAvailable(),
            lastDecisionExplanationProvider.getIfAvailable(),
            distributedQuarantineStatusProvider.getIfAvailable(),
            distributedThrottleStatusProvider.getIfAvailable(),
            clusterQuarantineReaderProvider.getIfAvailable(),
            clusterQuarantineWriterProvider.getIfAvailable(),
            clusterThrottleStoreProvider.getIfAvailable(),
            trainingPublishStatusProvider,
            trainingCandidatePublisherProvider);
    }
}
