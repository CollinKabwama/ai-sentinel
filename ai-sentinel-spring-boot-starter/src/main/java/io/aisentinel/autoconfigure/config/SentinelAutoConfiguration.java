package io.aisentinel.autoconfigure.config;

import io.aisentinel.core.SentinelPipeline;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.core.enforcement.EnforcementHandler;
import io.aisentinel.core.enforcement.MonitorOnlyEnforcementHandler;
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
import io.aisentinel.core.telemetry.DefaultTelemetryEmitter;
import io.aisentinel.core.telemetry.TelemetryConfig;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import io.aisentinel.autoconfigure.web.SentinelFilter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "ai.sentinel.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SentinelProperties.class)
public class SentinelAutoConfiguration {

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
    public StatisticalScorer statisticalScorer(SentinelProperties props) {
        int maxKeys = props.getInternalMapMaxKeys() > 0 ? props.getInternalMapMaxKeys() : 100_000;
        long ttlMs = props.getInternalMapTtl() != null ? props.getInternalMapTtl().toMillis() : 300_000L;
        int warmupMin = props.getWarmupMinSamples() >= 0 ? props.getWarmupMinSamples() : 2;
        double warmupScore = props.getWarmupScore() < 0 ? 0.4 : Math.min(1.0, props.getWarmupScore());
        return new StatisticalScorer(maxKeys, ttlMs, warmupMin, warmupScore);
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
        return new IsolationForestConfig(
            fallback,
            Math.max(1, ifProps.getMinTrainingSamples()),
            numTrees,
            maxDepth,
            ifProps.getRandomSeed(),
            ifProps.getSampleRate() < 0 ? 0.1 : Math.min(1.0, ifProps.getSampleRate())
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public IsolationForestScorer isolationForestScorer(BoundedTrainingBuffer isolationForestTrainingBuffer,
                                                      IsolationForestConfig isolationForestConfig) {
        return new IsolationForestScorer(isolationForestTrainingBuffer, isolationForestConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    public CompositeScorer compositeScorer(StatisticalScorer statisticalScorer,
                                           IsolationForestScorer isolationForestScorer,
                                           SentinelProperties props) {
        var composite = new CompositeScorer();
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
    @ConditionalOnBean(IsolationForestScorer.class)
    public IsolationForestRetrainScheduler isolationForestRetrainScheduler(IsolationForestScorer isolationForestScorer,
                                                                          SentinelProperties props) {
        return new IsolationForestRetrainScheduler(isolationForestScorer, props);
    }

    @Bean
    @ConditionalOnMissingBean
    public PolicyEngine policyEngine() {
        return new ThresholdPolicyEngine();
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
    public CompositeEnforcementHandler enforcementHandlerImpl(TelemetryEmitter telemetry, SentinelProperties props) {
        int maxKeys = props.getInternalMapMaxKeys() > 0 ? props.getInternalMapMaxKeys() : 100_000;
        long throttleTtlMs = props.getInternalMapTtl() != null ? props.getInternalMapTtl().toMillis() : 300_000L;
        return new CompositeEnforcementHandler(
            props.getBlockStatusCode(),
            props.getQuarantineDurationMs(),
            props.getThrottleRequestsPerSecond(),
            telemetry,
            maxKeys,
            throttleTtlMs
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public EnforcementHandler enforcementHandler(CompositeEnforcementHandler enforcementHandlerImpl,
                                                 TelemetryEmitter telemetry,
                                                 SentinelProperties props) {
        if (props.getMode() == SentinelProperties.Mode.MONITOR) {
            log.info("Sentinel running in MONITOR mode - no requests will be blocked");
            return new MonitorOnlyEnforcementHandler(enforcementHandlerImpl, telemetry);
        }
        return enforcementHandlerImpl;
    }

    @Bean
    @ConditionalOnMissingBean
    public SentinelPipeline sentinelPipeline(FeatureExtractor featureExtractor,
                                             CompositeScorer compositeScorer,
                                             PolicyEngine policyEngine,
                                             EnforcementHandler enforcementHandler,
                                             TelemetryEmitter telemetry,
                                             SentinelProperties props) {
        log.info("Sentinel pipeline configured (mode={})", props.getMode());
        return new SentinelPipeline(
            featureExtractor,
            compositeScorer,
            policyEngine,
            enforcementHandler,
            telemetry
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public SentinelFilter sentinelFilter(SentinelPipeline pipeline, SentinelProperties props) {
        return new SentinelFilter(pipeline, props);
    }
}
