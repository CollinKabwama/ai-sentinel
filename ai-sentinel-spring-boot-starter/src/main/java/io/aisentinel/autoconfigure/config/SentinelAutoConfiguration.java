package io.aisentinel.autoconfigure.config;

import io.aisentinel.core.SentinelPipeline;
import io.aisentinel.core.identity.spi.IdentityContextResolver;
import io.aisentinel.core.identity.spi.IdentityResponseHook;
import io.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import io.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import io.aisentinel.core.identity.spi.NoopTrustEvaluator;
import io.aisentinel.core.identity.spi.TrustEvaluator;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.core.enforcement.EnforcementHandler;
import io.aisentinel.core.enforcement.MonitorOnlyEnforcementHandler;
import io.aisentinel.distributed.enforcement.ClusterAwareEnforcementHandler;
import io.aisentinel.autoconfigure.distributed.OnDistributedQuarantineStatusNeededCondition;
import io.aisentinel.autoconfigure.distributed.DistributedThrottleStatus;
import io.aisentinel.autoconfigure.distributed.OnDistributedClusterThrottleEnabledCondition;
import io.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import io.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import io.aisentinel.distributed.quarantine.NoopClusterQuarantineReader;
import io.aisentinel.distributed.quarantine.NoopClusterQuarantineWriter;
import io.aisentinel.distributed.throttle.ClusterThrottleStore;
import io.aisentinel.distributed.throttle.NoopClusterThrottleStore;
import io.aisentinel.core.feature.DefaultFeatureExtractor;
import io.aisentinel.core.feature.FeatureExtractor;
import io.aisentinel.core.policy.PolicyEngine;
import io.aisentinel.core.policy.ThresholdPolicyEngine;
import io.aisentinel.core.scoring.BoundedTrainingBuffer;
import io.aisentinel.core.scoring.CompositeScorer;
import io.aisentinel.core.scoring.IsolationForestConfig;
import io.aisentinel.core.scoring.IsolationForestScorer;
import io.aisentinel.core.scoring.StatisticalScorer;
import io.aisentinel.core.store.BaselineStore;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.telemetry.DefaultTelemetryEmitter;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.telemetry.TelemetryConfig;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import io.aisentinel.autoconfigure.distributed.training.AsyncTrainingCandidatePublisher;
import io.aisentinel.autoconfigure.distributed.training.LoggingTrainingCandidateTransport;
import io.aisentinel.autoconfigure.distributed.training.TrainingCandidateTransport;
import io.aisentinel.autoconfigure.distributed.training.TrainingPublishStatus;
import io.aisentinel.autoconfigure.metrics.MicrometerSentinelMetrics;
import io.aisentinel.autoconfigure.web.SentinelFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.distributed.training.NoopTrainingCandidatePublisher;
import io.aisentinel.distributed.training.TrainingCandidatePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Conditional;

/**
 * Primary Spring Boot auto-configuration for AI-Sentinel: pipeline beans, filter, optional Isolation Forest and
 * distributed features. Most beans use {@link ConditionalOnMissingBean} so applications can override extension points
 * ({@link io.aisentinel.core.feature.FeatureExtractor}, {@link io.aisentinel.core.policy.PolicyEngine}, etc.).
 * <p>
 * Model registry refresh is registered separately in {@link io.aisentinel.autoconfigure.model.ModelRegistryAutoConfiguration}.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "ai.sentinel.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SentinelProperties.class)
public class SentinelAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public SentinelMetrics sentinelMetrics(MeterRegistry registry) {
        return new MicrometerSentinelMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean(SentinelMetrics.class)
    public SentinelMetrics noopSentinelMetrics() {
        return SentinelMetrics.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    public BaselineStore baselineStore(SentinelProperties props) {
        log.debug("Creating BaselineStore with ttl={}, maxKeys={}", props.getBaselineTtl(), props.getBaselineMaxKeys());
        return new BaselineStore(props.getBaselineTtl(), props.getBaselineMaxKeys());
    }

    @Bean
    @ConditionalOnMissingBean
    public FeatureExtractor featureExtractor(BaselineStore store, SentinelProperties props) {
        int maxKeys = props.getInternalMapMaxKeys() > 0 ? props.getInternalMapMaxKeys() : 100_000;
        long ttlMs = props.getInternalMapTtl() != null ? props.getInternalMapTtl().toMillis() : 300_000L;
        return new DefaultFeatureExtractor(store, maxKeys, ttlMs);
    }

    @Bean
    @ConditionalOnMissingBean
    public StatisticalScorer statisticalScorer(SentinelProperties props, SentinelMetrics sentinelMetrics) {
        int maxKeys = props.getInternalMapMaxKeys() > 0 ? props.getInternalMapMaxKeys() : 100_000;
        long ttlMs = props.getInternalMapTtl() != null ? props.getInternalMapTtl().toMillis() : 300_000L;
        int warmupMin = props.getWarmupMinSamples() >= 0 ? props.getWarmupMinSamples() : 2;
        double warmupScore = props.getWarmupScore() < 0 ? 0.4 : Math.min(1.0, props.getWarmupScore());
        return new StatisticalScorer(maxKeys, ttlMs, warmupMin, warmupScore, sentinelMetrics);
    }

    @Bean
    @ConditionalOnMissingBean
    public BoundedTrainingBuffer isolationForestTrainingBuffer(SentinelProperties props) {
        int size = props.getIsolationForest().getTrainingBufferSize();
        size = size <= 0 ? 10_000 : size;
        return new BoundedTrainingBuffer(size);
    }

    @Bean
    @ConditionalOnMissingBean
    public IsolationForestConfig isolationForestConfig(SentinelProperties props) {
        var ifProps = props.getIsolationForest();
        double fallback = ifProps.getFallbackScore() >= 0 ? Math.min(1.0, ifProps.getFallbackScore()) : 0.5;
        int numTrees = ifProps.getNumTrees() > 0 ? ifProps.getNumTrees() : 100;
        int maxDepth = ifProps.getMaxDepth() > 0 ? ifProps.getMaxDepth() : 10;
        double rej = ifProps.getTrainingRejectionScoreThreshold();
        if (rej < 0 || Double.isNaN(rej)) rej = 0.7;
        rej = Math.min(1.0, rej);
        return new IsolationForestConfig(
            fallback,
            Math.max(1, ifProps.getMinTrainingSamples()),
            numTrees,
            maxDepth,
            ifProps.getRandomSeed(),
            ifProps.getSampleRate() < 0 ? 0.1 : Math.min(1.0, ifProps.getSampleRate()),
            rej
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public StartupGrace sentinelStartupGrace(SentinelProperties props) {
        return new SentinelStartupGrace(props.getStartupGracePeriod());
    }

    @Bean
    @ConditionalOnMissingBean
    public IsolationForestScorer isolationForestScorer(BoundedTrainingBuffer isolationForestTrainingBuffer,
                                                      IsolationForestConfig isolationForestConfig,
                                                      SentinelMetrics sentinelMetrics) {
        return new IsolationForestScorer(isolationForestTrainingBuffer, isolationForestConfig, sentinelMetrics);
    }

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public MeterBinder aisentinelSystemAndTrainingGauges(StatisticalScorer statisticalScorer,
                                                         FeatureExtractor featureExtractor,
                                                         CompositeEnforcementHandler enforcementHandlerImpl,
                                                         IsolationForestScorer isolationForestScorer) {
        return registry -> {
            registry.gauge("aisentinel.training.samples.accepted", isolationForestScorer,
                s -> (double) s.getAcceptedTrainingSampleCount());
            registry.gauge("aisentinel.training.samples.rejected", isolationForestScorer,
                s -> (double) s.getRejectedTrainingSampleCount());
            registry.gauge("aisentinel.cache.state.size", statisticalScorer,
                s -> (double) s.metricsStateEntryCount());
            registry.gauge("aisentinel.cache.endpointHistory.size", featureExtractor,
                f -> (double) f.metricsEndpointHistoryEntryCount());
            registry.gauge("aisentinel.cache.throttle.size", enforcementHandlerImpl,
                h -> (double) h.getThrottleCount());
            registry.gauge("aisentinel.cache.quarantine.size", enforcementHandlerImpl,
                h -> (double) h.getQuarantineCount());
            registry.gauge("aisentinel.isolation_forest.model.source", isolationForestScorer,
                s -> (double) s.getActiveModelSource().ordinal());
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public CompositeScorer compositeScorer(StatisticalScorer statisticalScorer,
                                           IsolationForestScorer isolationForestScorer,
                                           SentinelProperties props,
                                           SentinelMetrics sentinelMetrics) {
        var composite = new CompositeScorer(sentinelMetrics);
        composite.addScorer(statisticalScorer, 1.0);
        if (props.getIsolationForest().isEnabled()) {
            double weight = props.getIsolationForest().getScoreWeight();
            if (weight <= 0) weight = 0.5;
            log.debug("Adding IsolationForestScorer with weight {}", weight);
            composite.addScorer(isolationForestScorer, weight);
        }
        return composite;
    }

    @Bean
    @ConditionalOnProperty(name = "ai.sentinel.isolation-forest.enabled", havingValue = "true")
    @Conditional(OnIsolationForestLocalRetrainCondition.class)
    @ConditionalOnBean(IsolationForestScorer.class)
    public IsolationForestRetrainScheduler isolationForestRetrainScheduler(IsolationForestScorer isolationForestScorer,
                                                                          SentinelProperties props) {
        return new IsolationForestRetrainScheduler(isolationForestScorer, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public PolicyEngine policyEngine(SentinelProperties props) {
        return new ThresholdPolicyEngine(
            props.getThresholdModerate(),
            props.getThresholdElevated(),
            props.getThresholdHigh(),
            props.getThresholdCritical()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public TelemetryEmitter telemetryEmitter(ObjectProvider<MeterRegistry> registryProvider,
                                             SentinelProperties props) {
        var t = props.getTelemetry();
        var verbosity = switch (t.getLogVerbosity().toUpperCase()) {
            case "FULL" -> TelemetryConfig.LogVerbosity.FULL;
            case "ANOMALY_ONLY" -> TelemetryConfig.LogVerbosity.ANOMALY_ONLY;
            case "SAMPLED" -> TelemetryConfig.LogVerbosity.SAMPLED;
            case "NONE" -> TelemetryConfig.LogVerbosity.NONE;
            default -> TelemetryConfig.LogVerbosity.ANOMALY_ONLY;
        };
        var telemetryConfig = new TelemetryConfig(verbosity, t.getLogScoreThreshold(), t.getLogSampleRate());
        log.debug("Creating TelemetryEmitter with verbosity={}", verbosity);
        return new DefaultTelemetryEmitter(registryProvider.getIfAvailable(), telemetryConfig);
    }

    @Bean
    @ConditionalOnMissingBean(name = "enforcementHandlerImpl")
    public CompositeEnforcementHandler enforcementHandlerImpl(TelemetryEmitter telemetry, SentinelProperties props,
                                                               ObjectProvider<ClusterQuarantineWriter> clusterQuarantineWriterProvider,
                                                               ObjectProvider<ClusterThrottleStore> clusterThrottleStoreProvider) {
        int maxKeys = props.getInternalMapMaxKeys() > 0 ? props.getInternalMapMaxKeys() : 100_000;
        long throttleTtlMs = props.getInternalMapTtl() != null ? props.getInternalMapTtl().toMillis() : 300_000L;
        ClusterQuarantineWriter writer = clusterQuarantineWriterProvider.getIfAvailable();
        if (writer == null) {
            writer = NoopClusterQuarantineWriter.INSTANCE;
        }
        ClusterThrottleStore throttleStore = clusterThrottleStoreProvider.getIfAvailable();
        if (throttleStore == null) {
            throttleStore = NoopClusterThrottleStore.INSTANCE;
        }
        return new CompositeEnforcementHandler(
            props.getBlockStatusCode(),
            props.getQuarantineDurationMs(),
            props.getThrottleRequestsPerSecond(),
            telemetry,
            maxKeys,
            throttleTtlMs,
            props.getEnforcementScope(),
            writer,
            throttleStore,
            props.getDistributed().getTenantId()
        );
    }

    @Bean
    @ConditionalOnMissingBean(ClusterThrottleStore.class)
    public ClusterThrottleStore clusterThrottleStoreNoop() {
        return NoopClusterThrottleStore.INSTANCE;
    }

    @Bean
    @ConditionalOnProperty(name = "ai.sentinel.distributed.cluster-quarantine-read-enabled", havingValue = "true")
    @ConditionalOnMissingBean(ClusterQuarantineReader.class)
    public ClusterQuarantineReader noopClusterQuarantineReader() {
        return NoopClusterQuarantineReader.INSTANCE;
    }

    /**
     * Runs after the context is ready so distributed quarantine reader/writer beans are resolved.
     */
    @Bean
    @Conditional(OnDistributedQuarantineStatusNeededCondition.class)
    public ApplicationRunner distributedQuarantineStartupRunner(SentinelProperties props,
                                                                ObjectProvider<ClusterQuarantineReader> readerProvider,
                                                                ObjectProvider<ClusterQuarantineWriter> writerProvider) {
        return args -> {
            var d = props.getDistributed();
            String tenant = d.getTenantId() != null && !d.getTenantId().isBlank() ? d.getTenantId() : "default";
            if (d.isClusterQuarantineReadEnabled()) {
                var reader = readerProvider.getIfAvailable();
                String readerKind = reader == null
                    ? "none"
                    : (reader == NoopClusterQuarantineReader.INSTANCE ? "noop" : reader.getClass().getSimpleName());
                log.info(
                    "AI-Sentinel distributed quarantine read: distributed.enabled={}, clusterQuarantineRead=true, "
                        + "redis.enabled={}, reader={}, cache.enabled={}, tenantId={}",
                    d.isEnabled(),
                    d.getRedis().isEnabled(),
                    readerKind,
                    d.getCache().isEnabled(),
                    tenant);
            }
            if (d.isClusterQuarantineWriteEnabled()) {
                var w = writerProvider.getIfAvailable();
                String writerKind = w == null
                    ? "none"
                    : (w == NoopClusterQuarantineWriter.INSTANCE ? "noop" : w.getClass().getSimpleName());
                log.info(
                    "AI-Sentinel distributed quarantine write: distributed.enabled={}, clusterQuarantineWrite=true, "
                        + "redis.enabled={}, writer={}, tenantId={}",
                    d.isEnabled(),
                    d.getRedis().isEnabled(),
                    writerKind,
                    tenant);
            }
        };
    }

    @Bean
    @ConditionalOnBean({DistributedQuarantineStatus.class, MeterRegistry.class})
    public MeterBinder distributedQuarantineReaderDegradedGauge(DistributedQuarantineStatus status) {
        return registry -> registry.gauge("aisentinel.distributed.quarantine.degraded", status,
            s -> s.isRedisReaderDegraded() ? 1.0 : 0.0);
    }

    @Bean
    @ConditionalOnBean({DistributedQuarantineStatus.class, MeterRegistry.class})
    public MeterBinder distributedQuarantineWriterDegradedGauge(DistributedQuarantineStatus status) {
        return registry -> registry.gauge("aisentinel.distributed.quarantine.writer.degraded", status,
            s -> s.isRedisWriterDegraded() ? 1.0 : 0.0);
    }

    @Bean
    @ConditionalOnBean({DistributedThrottleStatus.class, MeterRegistry.class})
    public MeterBinder distributedThrottleDegradedGauge(DistributedThrottleStatus status) {
        return registry -> registry.gauge("aisentinel.distributed.throttle.degraded", status,
            s -> s.isRedisThrottleDegraded() ? 1.0 : 0.0);
    }

    @Bean
    @ConditionalOnProperty(name = "ai.sentinel.distributed.training-publish-enabled", havingValue = "true")
    public TrainingPublishStatus trainingPublishStatus() {
        return new TrainingPublishStatus();
    }

    @Bean
    @ConditionalOnMissingBean(TrainingCandidateTransport.class)
    public TrainingCandidateTransport loggingTrainingCandidateTransport(ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper om = objectMapperProvider.getIfAvailable();
        if (om == null) {
            om = new ObjectMapper();
        }
        return new LoggingTrainingCandidateTransport(om);
    }

    @Bean
    @ConditionalOnMissingBean(TrainingCandidatePublisher.class)
    public TrainingCandidatePublisher trainingCandidatePublisher(SentinelProperties props,
                                                                   SentinelMetrics sentinelMetrics,
                                                                   ObjectProvider<TrainingPublishStatus> trainingPublishStatusProvider,
                                                                   ObjectProvider<TrainingCandidateTransport> trainingCandidateTransportProvider) {
        if (!props.getDistributed().isTrainingPublishEnabled()) {
            return NoopTrainingCandidatePublisher.INSTANCE;
        }
        TrainingPublishStatus status = trainingPublishStatusProvider.getIfAvailable();
        if (status == null) {
            status = new TrainingPublishStatus();
        }
        TrainingCandidateTransport transport = trainingCandidateTransportProvider.getIfAvailable();
        if (transport == null) {
            ObjectMapper om = new ObjectMapper();
            transport = new LoggingTrainingCandidateTransport(om);
        }
        return new AsyncTrainingCandidatePublisher(props, sentinelMetrics, status, transport);
    }

    @Bean
    @ConditionalOnBean({TrainingPublishStatus.class, MeterRegistry.class})
    public MeterBinder trainingPublishDegradedGauge(TrainingPublishStatus status) {
        return registry -> registry.gauge("aisentinel.distributed.training.publish.degraded", status,
            s -> s.isDegraded() ? 1.0 : 0.0);
    }

    @Bean
    @Conditional(OnDistributedClusterThrottleEnabledCondition.class)
    public ApplicationRunner distributedClusterThrottleStartupRunner(SentinelProperties props,
                                                                     ObjectProvider<ClusterThrottleStore> throttleProvider) {
        return args -> {
            var t = throttleProvider.getIfAvailable();
            String kind = t == null
                ? "none"
                : (t == NoopClusterThrottleStore.INSTANCE ? "noop" : t.getClass().getSimpleName());
            log.info(
                "AI-Sentinel distributed cluster throttle: store={}, window={}, maxRequestsPerWindow={}, tenantId={}, lookupTimeout={}",
                kind,
                props.getDistributed().getClusterThrottleWindow(),
                props.getDistributed().getClusterThrottleMaxRequestsPerWindow(),
                props.getDistributed().getTenantId(),
                props.getDistributed().getRedis().getLookupTimeout());
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public EnforcementHandler enforcementHandler(CompositeEnforcementHandler enforcementHandlerImpl,
                                                 ObjectProvider<ClusterQuarantineReader> clusterQuarantineReaderProvider,
                                                 TelemetryEmitter telemetry,
                                                 SentinelProperties props) {
        EnforcementHandler delegate = enforcementHandlerImpl;
        if (props.getDistributed().isClusterQuarantineReadEnabled()) {
            ClusterQuarantineReader reader = clusterQuarantineReaderProvider.getIfAvailable();
            if (reader == null) {
                reader = NoopClusterQuarantineReader.INSTANCE;
            }
            delegate = new ClusterAwareEnforcementHandler(
                enforcementHandlerImpl,
                reader,
                props.getDistributed().getTenantId(),
                props.getEnforcementScope());
        }
        if (props.getMode() == SentinelProperties.Mode.MONITOR) {
            log.info("Sentinel running in MONITOR mode - no requests will be blocked");
            return new MonitorOnlyEnforcementHandler(delegate, telemetry);
        }
        return delegate;
    }

    @Bean
    @ConditionalOnMissingBean
    public SentinelPipeline sentinelPipeline(FeatureExtractor featureExtractor,
                                             CompositeScorer compositeScorer,
                                             PolicyEngine policyEngine,
                                             EnforcementHandler enforcementHandler,
                                             TelemetryEmitter telemetry,
                                             StartupGrace sentinelStartupGrace,
                                             SentinelMetrics sentinelMetrics,
                                             TrainingCandidatePublisher trainingCandidatePublisher,
                                             SentinelProperties props,
                                             ObjectProvider<IdentityContextResolver> identityContextResolverProvider,
                                             ObjectProvider<TrustEvaluator> trustEvaluatorProvider,
                                             ObjectProvider<IdentityResponseHook> identityResponseHookProvider) {
        log.info("Sentinel pipeline configured (mode={})", props.getMode());
        String nodeId = props.getDistributed().getTrainingPublisherNodeId();
        if (nodeId == null) {
            nodeId = "";
        }
        IdentityContextResolver identityContextResolver = identityContextResolverProvider.getIfAvailable();
        if (identityContextResolver == null) {
            identityContextResolver = NoopIdentityContextResolver.INSTANCE;
        }
        TrustEvaluator trustEvaluator = trustEvaluatorProvider.getIfAvailable();
        if (trustEvaluator == null) {
            trustEvaluator = NoopTrustEvaluator.INSTANCE;
        }
        IdentityResponseHook identityResponseHook = identityResponseHookProvider.getIfAvailable();
        if (identityResponseHook == null) {
            identityResponseHook = NoopIdentityResponseHook.INSTANCE;
        }
        return new SentinelPipeline(
            featureExtractor,
            compositeScorer,
            compositeScorer,
            policyEngine,
            enforcementHandler,
            telemetry,
            sentinelStartupGrace,
            sentinelMetrics,
            trainingCandidatePublisher,
            props.getEnforcementScope(),
            props.getDistributed().getTenantId(),
            nodeId,
            props.getMode().name(),
            identityContextResolver,
            trustEvaluator,
            identityResponseHook
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SentinelFilter sentinelFilter(SentinelPipeline pipeline, SentinelProperties props, SentinelMetrics sentinelMetrics) {
        return new SentinelFilter(pipeline, props, sentinelMetrics);
    }
}
