package dev.aisentinel.core.characterization;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
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
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.StatisticalFeatureNames;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes feature-vector length contracts and evaluation-status behavior for unknown scorers.
 */
class FeatureSchemaAndObservabilityTest {

    @Test
    void statisticalAndIsolationForestArrayLengths_areStableContracts() {
        RequestFeatures f = RequestFeatures.builder()
            .identityHash("h").endpoint("/e").timestampMillis(1)
            .requestsPerWindow(1).endpointEntropy(0).endpointConcentration(0)
            .tokenAgeSeconds(-1).parameterCount(0).payloadSizeBytes(0)
            .headerFingerprintHash(0).ipBucket(0).build();

        assertThat(f.toStatisticalArray()).hasSize(StatisticalFeatureNames.NAMES.length);
        assertThat(f.toStatisticalArray()).hasSize(6);
        assertThat(f.toIsolationForestArray()).hasSize(5);
        assertThat(f.toArray()).hasSize(7);
        System.out.printf(Locale.ROOT,
            "feature lengths stat=%d if=%d full=%d names=%d%n",
            f.toStatisticalArray().length, f.toIsolationForestArray().length,
            f.toArray().length, StatisticalFeatureNames.NAMES.length);
    }

    @Test
    void statisticalScorer_handlesShorterThanExpectedFeatureVectorViaMinDim() {
        // Build features normally — RequestFeatures always produces fixed arrays.
        // Document that there is no public API to inject wrong-length arrays into scorers;
        // mismatch risk lives at training serialization (hardcoded STAT_FEATURE_LEN / IF_FEATURE_LEN).
        StatisticalScorer scorer = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        RequestFeatures f = RequestFeatures.builder()
            .identityHash("h").endpoint("/e").timestampMillis(1)
            .requestsPerWindow(3).endpointEntropy(0.1).endpointConcentration(0.5)
            .tokenAgeSeconds(-1).parameterCount(1).payloadSizeBytes(10)
            .headerFingerprintHash(1).ipBucket(1).build();
        scorer.update(f);
        scorer.update(f);
        double s = scorer.score(f);
        assertThat(Double.isFinite(s)).isTrue();
        System.out.printf(Locale.ROOT,
            "RequestFeatures fixes lengths; training publisher hardcodes 7/5 — schema versioning gap remains%n");
    }

    @Test
    void customScorerWithoutKnownTypesStillYieldsCompleteStatus() throws Exception {
        AnomalyScorer custom = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return 0.15;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        // EvaluationStatusCollector is package-private; exercise via engine evaluate path
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            custom,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQ.INSTANCE,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
        RiskDecision d = engine.evaluate(
            new MapHttpRequestView().requestUri("/c").method("GET"),
            "c",
            RequestFeatures.builder()
                .identityHash("c").endpoint("/c").timestampMillis(1)
                .requestsPerWindow(1).endpointEntropy(0).endpointConcentration(0)
                .tokenAgeSeconds(-1).parameterCount(0).payloadSizeBytes(0)
                .headerFingerprintHash(0).ipBucket(0).build(),
            new RequestContext());
        Set<EvaluationStatus> statuses = d.evaluationStatuses();
        System.out.printf(Locale.ROOT, "customScorer statuses=%s action=%s score=%.4f%n",
            statuses, d.action(), d.anomalyScore());
        // Custom scorers get COMPLETE when collector cannot attach STATISTICAL_* / MODEL_* markers
        assertThat(statuses).contains(EvaluationStatus.COMPLETE);
        assertThat(statuses).doesNotContain(EvaluationStatus.STATISTICAL_LIVE);
    }

    private enum NeverQ implements EnforcementHandler {
        INSTANCE;
        @Override
        public boolean apply(EnforcementAction a, HttpRequestView r, EnforcementResponse s, String i, String e) {
            return true;
        }
        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }
}
