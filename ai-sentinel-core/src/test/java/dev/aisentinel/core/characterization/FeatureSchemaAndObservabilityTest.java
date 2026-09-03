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
import dev.aisentinel.core.model.FeatureSchema;
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
        assertThat(f.toStatisticalArray()).hasSize(FeatureSchema.STATISTICAL_DIMENSION);
        assertThat(f.toIsolationForestArray()).hasSize(FeatureSchema.ISOLATION_FOREST_DIMENSION);
        assertThat(f.toArray()).hasSize(FeatureSchema.EXPORT_DIMENSION);
        assertThat(FeatureSchema.VERSION).isEqualTo(1);
    }

    @Test
    void statisticalScorer_handlesFixedLengthFeatureVectorsFromRequestFeatures() {
        // RequestFeatures always produces schema-sized arrays; training export uses FeatureSchema dims.
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
    }

    @Test
    void customScorerWithoutContributorYieldsCompleteStatus() throws Exception {
        AnomalyScorer custom = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return 0.15;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
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
