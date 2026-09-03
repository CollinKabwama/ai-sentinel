package dev.aisentinel.core.contract;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.FeatureExtractor;
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
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local path vs contract bridge semantic equivalence for representative decisions.
 */
class LocalEvaluationBridgeEquivalenceTest {

    @Test
    void lowRiskAllowEquivalent() {
        assertEquivalent(0.05, EnforcementAction.ALLOW);
    }

    @Test
    void monitorBandEquivalent() {
        assertEquivalent(0.35, EnforcementAction.MONITOR);
    }

    @Test
    void blockBandEquivalent() {
        assertEquivalent(0.7, EnforcementAction.BLOCK);
    }

    @Test
    void quarantineBandEquivalent() {
        assertEquivalent(0.9, EnforcementAction.QUARANTINE);
    }

    @Test
    void invalidScoreEquivalent() {
        FixedScorer scorer = new FixedScorer(Double.NaN);
        SentinelDecisionEngine engine = engine(scorer);
        MapHttpRequestView view = view();
        String identity = "hash-eq-1";
        FeatureExtractor extractor = fixedExtractor(identity);
        RiskDecision direct = engine.evaluate(view, identity, extractor.extract(view, identity, new RequestContext()),
            new RequestContext());
        EvaluationRequest request = EvaluationContractMapper.fromHttpRequestView(view, identity, "corr-inv");
        EvaluationResponse viaContract = new LocalEvaluationBridge(extractor, engine).evaluate(request);

        assertThat(direct.hasStatus(EvaluationStatus.INVALID_SCORE)).isTrue();
        assertThat(direct.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(viaContract.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(viaContract.evaluationStatuses()).contains("INVALID_SCORE");
        assertThat(viaContract.anomalyScore()).isNull();
        assertThat(viaContract.policyScore()).isNull();
        assertThat(viaContract.factors()).extracting(ContractRiskFactor::code)
            .contains("INVALID_SCORE_SIGNAL");
    }

    @Test
    void explanationDoesNotChangeActionAcrossPaths() {
        FixedScorer scorer = new FixedScorer(0.35);
        SentinelDecisionEngine engine = engine(scorer);
        MapHttpRequestView view = view();
        String identity = "hash-eq-2";
        FeatureExtractor extractor = fixedExtractor(identity);
        RiskDecision direct = engine.evaluate(view, identity, extractor.extract(view, identity, new RequestContext()),
            new RequestContext());
        EvaluationResponse viaContract = new LocalEvaluationBridge(extractor, engine)
            .evaluate(EvaluationContractMapper.fromHttpRequestView(view, identity, "corr-2"));
        assertThat(viaContract.action()).isEqualTo(direct.action());
        assertThat(viaContract.action()).isEqualTo(EnforcementAction.MONITOR);
    }

    private static void assertEquivalent(double score, EnforcementAction expected) {
        FixedScorer scorer = new FixedScorer(score);
        SentinelDecisionEngine engine = engine(scorer);
        MapHttpRequestView view = view();
        String identity = "hash-" + expected.name();
        FeatureExtractor extractor = fixedExtractor(identity);
        RequestFeatures features = extractor.extract(view, identity, new RequestContext());
        RiskDecision direct = engine.evaluate(view, identity, features, new RequestContext());
        EvaluationResponse viaContract = new LocalEvaluationBridge(extractor, engine)
            .evaluate(EvaluationContractMapper.fromHttpRequestView(view, identity, "corr-" + expected.name()));

        assertThat(direct.action()).isEqualTo(expected);
        assertThat(viaContract.action()).isEqualTo(expected);
        assertThat(viaContract.anomalyScore()).isEqualTo(direct.anomalyScore());
        assertThat(viaContract.policyScore()).isEqualTo(direct.policyScore());
        assertThat(viaContract.evaluationStatuses())
            .containsExactlyElementsOf(direct.evaluationStatuses().stream().map(Enum::name).sorted().toList());
        assertThat(viaContract.factors()).hasSameSizeAs(direct.explanation().factors());
        if (direct.explanation().advice() == null) {
            assertThat(viaContract.advice()).isNull();
        } else {
            assertThat(viaContract.advice().code()).isEqualTo(direct.explanation().advice().code().name());
        }
    }

    private static FeatureExtractor fixedExtractor(String identity) {
        return (request, identityHash, ctx) -> RequestFeatures.builder()
            .identityHash(identity)
            .endpoint(request.getRequestURI())
            .timestampMillis(1L)
            .requestsPerWindow(1)
            .endpointEntropy(0.1)
            .endpointConcentration(0.1)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(1L)
            .ipBucket(1)
            .build();
    }

    private static MapHttpRequestView view() {
        return new MapHttpRequestView()
            .requestUri("/api/hello")
            .method("GET")
            .remoteAddr("127.0.0.1")
            .header("content-length", "0");
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NEVER_QUARANTINED,
            (TelemetryEmitter) event -> {
            },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE);
    }

    private static final EnforcementHandler NEVER_QUARANTINED = new EnforcementHandler() {
        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("bridge tests must not apply enforcement");
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    };

    private static final class FixedScorer implements AnomalyScorer {
        private final double score;

        private FixedScorer(double score) {
            this.score = score;
        }

        @Override
        public double score(RequestFeatures features) {
            return score;
        }

        @Override
        public void update(RequestFeatures features) {
        }
    }
}
