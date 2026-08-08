package dev.aisentinel.core.regression;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.enforcement.DiscardingEnforcementResponse;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.BoundedTrainingBuffer;
import dev.aisentinel.core.scoring.IsolationForestConfig;
import dev.aisentinel.core.scoring.IsolationForestModel;
import dev.aisentinel.core.scoring.IsolationForestModelCodec;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.IsolationForestTrainer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.store.BaselineStore;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import dev.aisentinel.model.ModelArtifactMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Restart / recovery regressions for process-local detector and enforcement state,
 * plus Isolation Forest registry install failure modes after a cold scorer start.
 * <p>
 * Fresh-instance construction models JVM restart for heap-only state. Redis reconnect
 * recovery is covered in the starter module (status fail→success), not here.
 */
class RuntimeResilienceRecoveryRegressionTest {

    private static final String IDENTITY = "id-restart";
    private static final String ENDPOINT = "/api/hello";

    @Test
    void localRestart_statisticalAndRequestBaselineReturnToWarmup() {
        BaselineStore liveStore = new BaselineStore(Duration.ofMinutes(5), 1000);
        StatisticalScorer liveScorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        for (int i = 0; i < 10; i++) {
            liveStore.incrementAndGet(IDENTITY + "|" + ENDPOINT);
            liveScorer.update(features(10.0));
        }
        assertThat(liveScorer.isWarmup(features(10.0))).isFalse();
        assertThat(liveStore.get(IDENTITY + "|" + ENDPOINT)).isPositive();

        BaselineStore restartedStore = new BaselineStore(Duration.ofMinutes(5), 1000);
        StatisticalScorer restartedScorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        assertThat(restartedStore.size()).isZero();
        assertThat(restartedStore.get(IDENTITY + "|" + ENDPOINT)).isZero();
        assertThat(restartedScorer.metricsStateEntryCount()).isZero();
        assertThat(restartedScorer.isWarmup(features(10.0))).isTrue();
    }

    @Test
    void localRestart_warmupLearningThenLive_noStaleSkipState() {
        StatisticalScorer restarted = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        EnforcementHandler neverQuarantined = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request,
                                 EnforcementResponse response, String identityHash, String endpoint) {
                return true;
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return false;
            }
        };
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            restarted,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            neverQuarantined,
            event -> { },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            BaselineLifecycle.disabled()
        );

        RiskDecision w1 = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        RiskDecision w2 = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(w1.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w2.hasStatus(EvaluationStatus.STATISTICAL_WARMUP)).isTrue();
        assertThat(w1.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isFalse();

        RiskDecision live = engine.evaluate(shell(), IDENTITY, features(5.0), new RequestContext());
        assertThat(live.hasStatus(EvaluationStatus.STATISTICAL_LIVE)).isTrue();
    }

    @Test
    void localRestart_clearsInMemoryQuarantineAndThrottle() throws Exception {
        TelemetryEmitter telemetry = mock(TelemetryEmitter.class);
        HttpRequestView request = mock(HttpRequestView.class);
        CompositeEnforcementHandler live = new CompositeEnforcementHandler(429, 60_000L, 1.0, telemetry);
        live.apply(EnforcementAction.QUARANTINE, request, DiscardingEnforcementResponse.INSTANCE, IDENTITY, ENDPOINT);
        assertThat(live.tryAcquireThrottlePermit(IDENTITY, ENDPOINT)).isTrue();
        assertThat(live.isQuarantined(IDENTITY, ENDPOINT)).isTrue();
        assertThat(live.getThrottleCount()).isPositive();

        CompositeEnforcementHandler restarted = new CompositeEnforcementHandler(429, 60_000L, 1.0, telemetry);
        assertThat(restarted.isQuarantined(IDENTITY, ENDPOINT)).isFalse();
        assertThat(restarted.getQuarantineCount()).isZero();
        assertThat(restarted.getThrottleCount()).isZero();
    }

    @Test
    void registryRecovery_coldStartInstallsValidArtifact() throws Exception {
        IsolationForestScorer scorer = newScorer();
        assertThat(scorer.isModelLoaded()).isFalse();

        byte[] payload = encodeSampleModel();
        var meta = metaFor(payload, "reg-cold", 5);
        assertThat(scorer.tryInstallFromRegistry(meta, payload)).isTrue();
        assertThat(scorer.isModelLoaded()).isTrue();
        assertThat(scorer.getActiveModelSource()).isEqualTo(IsolationForestScorer.ActiveModelSource.REGISTRY);
        assertThat(scorer.getRegistryArtifactVersion()).isEqualTo("reg-cold");
    }

    @Test
    void registryRecovery_corruptPayloadKeepsNoModelOnColdStart() throws Exception {
        IsolationForestScorer scorer = newScorer();
        byte[] garbage = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        String hash = TrainingFingerprintHashes.sha256HexBytes(garbage);
        var meta = new ModelArtifactMetadata(
            "default", "corrupt", ModelArtifactMetadata.CURRENT_ARTIFACT_SCHEMA_VERSION, 2,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1, 1L, 5, 5, 3, 3, hash);
        assertThat(scorer.tryInstallFromRegistry(meta, garbage)).isFalse();
        assertThat(scorer.isModelLoaded()).isFalse();
        assertThat(scorer.getRegistryInstallFailureCount()).isPositive();
    }

    @Test
    void registryRecovery_dimensionMismatchRejected() throws Exception {
        IsolationForestScorer scorer = newScorer();
        byte[] payload = encodeSampleModel();
        var meta = metaFor(payload, "dim-bad", 7); // model is 5-dim
        assertThat(scorer.tryInstallFromRegistry(meta, payload)).isFalse();
        assertThat(scorer.isModelLoaded()).isFalse();
    }

    @Test
    void registryRecovery_schemaMismatchRejected() throws Exception {
        IsolationForestScorer scorer = newScorer();
        byte[] payload = encodeSampleModel();
        String hash = TrainingFingerprintHashes.sha256HexBytes(payload);
        var meta = new ModelArtifactMetadata(
            "default", "schema-bad", 99, 2,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1, 1L, 5, 5, 3, 3, hash);
        assertThat(scorer.tryInstallFromRegistry(meta, payload)).isFalse();
        assertThat(scorer.isModelLoaded()).isFalse();
    }

    @Test
    void registryRecovery_missingArtifactPointerRejected() {
        IsolationForestScorer scorer = newScorer();
        assertThat(scorer.tryInstallFromRegistry(null, new byte[] {1})).isFalse();
        assertThat(scorer.tryInstallFromRegistry(metaFor(new byte[] {1}, "x", 5), null)).isFalse();
        assertThat(scorer.isModelLoaded()).isFalse();
    }

    private static IsolationForestScorer newScorer() {
        BoundedTrainingBuffer buf = new BoundedTrainingBuffer(100);
        IsolationForestConfig cfg = new IsolationForestConfig(0.5, 2, 5, 3, 1L, 1.0, 0.99);
        return new IsolationForestScorer(buf, cfg);
    }

    private static byte[] encodeSampleModel() throws Exception {
        List<double[]> samples = List.of(
            new double[] {1, 2, 3, 4, 5},
            new double[] {2, 2, 2, 2, 2},
            new double[] {3, 3, 3, 3, 3}
        );
        IsolationForestModel m = new IsolationForestTrainer(5, 3, 42L).train(samples);
        return IsolationForestModelCodec.encode(m);
    }

    private static ModelArtifactMetadata metaFor(byte[] payload, String version, int featureDim) {
        return new ModelArtifactMetadata(
            "default",
            version,
            ModelArtifactMetadata.CURRENT_ARTIFACT_SCHEMA_VERSION,
            2,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1,
            500L,
            featureDim,
            5,
            3,
            3,
            TrainingFingerprintHashes.sha256HexBytes(payload)
        );
    }

    private static HttpRequestView shell() {
        return new MapHttpRequestView().requestUri(ENDPOINT).method("GET");
    }

    private static RequestFeatures features(double rpw) {
        return RequestFeatures.builder()
            .identityHash(IDENTITY)
            .endpoint(ENDPOINT)
            .timestampMillis(System.currentTimeMillis())
            .requestsPerWindow(rpw)
            .endpointEntropy(0)
            .endpointConcentration(1)
            .tokenAgeSeconds(60)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }
}
