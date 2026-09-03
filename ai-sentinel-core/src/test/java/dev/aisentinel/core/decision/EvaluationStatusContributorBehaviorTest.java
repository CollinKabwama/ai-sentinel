package dev.aisentinel.core.decision;

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
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationStatusContributorBehaviorTest {

    private static final RequestFeatures FEATURES = RequestFeatures.builder()
        .identityHash("id").endpoint("/e").timestampMillis(1L)
        .requestsPerWindow(1).endpointEntropy(0).endpointConcentration(0)
        .tokenAgeSeconds(-1).parameterCount(0).payloadSizeBytes(0)
        .headerFingerprintHash(0).ipBucket(0).build();

    @Test
    void builtInStatisticalContributorReportsLiveAfterWarmup() {
        StatisticalScorer statistical = new StatisticalScorer(1000, 60_000L, 2, 0.4);
        statistical.update(FEATURES);
        statistical.update(FEATURES);
        RiskDecision d = evaluate(statistical);
        assertThat(d.evaluationStatuses()).contains(EvaluationStatus.STATISTICAL_LIVE);
        assertThat(d.evaluationStatuses()).contains(EvaluationStatus.COMPLETE);
    }

    @Test
    void scorerWithoutContributorYieldsCompleteOnly() {
        RiskDecision d = evaluate(new FixedScoreScorer(0.15));
        assertThat(d.evaluationStatuses()).containsExactly(EvaluationStatus.COMPLETE);
    }

    @Test
    void customContributorStatusesAppearWithoutConcreteBuiltInTypes() {
        RiskDecision d = evaluate(new StatusAnnotatingScorer(0.15, EvaluationStatus.STATISTICAL_LIVE));
        assertThat(d.evaluationStatuses()).contains(EvaluationStatus.STATISTICAL_LIVE);
        assertThat(d.evaluationStatuses()).contains(EvaluationStatus.COMPLETE);
        assertThat(d.anomalyScore()).isEqualTo(0.15);
        assertThat(d.action()).isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void multipleChildContributorsAggregateDeterministically() {
        CompositeScorer composite = new CompositeScorer();
        composite.addScorer(new StatusAnnotatingScorer(0.1, EvaluationStatus.STATISTICAL_LIVE), 1.0);
        composite.addScorer(new StatusAnnotatingScorer(0.1, EvaluationStatus.MODEL_FALLBACK_USED), 1.0);
        RiskDecision d = evaluate(composite);
        assertThat(d.evaluationStatuses()).contains(
            EvaluationStatus.STATISTICAL_LIVE,
            EvaluationStatus.MODEL_FALLBACK_USED);
        assertThat(d.evaluationStatuses()).doesNotContain(EvaluationStatus.COMPLETE);
    }

    @Test
    void contributorFailureMarksDegradedWithoutFailingEvaluation() {
        RiskDecision d = evaluate(new ThrowingStatusContributorScorer(0.2));
        assertThat(d.evaluationStatuses()).contains(EvaluationStatus.DEGRADED);
        assertThat(d.anomalyScore()).isEqualTo(0.2);
        assertThat(d.action()).isEqualTo(EnforcementAction.MONITOR);
    }

    @Test
    void contributorStatusesDoNotChangeEnforcementAction() {
        AtomicInteger contributeCalls = new AtomicInteger();
        AnomalyScorer scorer = new StatusAnnotatingScorer(0.9, EvaluationStatus.STATISTICAL_LIVE) {
            @Override
            public void contributeEvaluationStatuses(RequestFeatures features,
                                                      EvaluationStatusContributionContext context) {
                contributeCalls.incrementAndGet();
                super.contributeEvaluationStatuses(features, context);
                context.add(EvaluationStatus.MODEL_UNAVAILABLE);
            }
        };
        RiskDecision d = evaluate(scorer);
        assertThat(contributeCalls.get()).isPositive();
        assertThat(d.evaluationStatuses()).contains(EvaluationStatus.MODEL_UNAVAILABLE);
        // Score 0.9 still maps to QUARANTINE under default thresholds; statuses do not override.
        assertThat(d.action()).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void contributorCannotFabricateInvalidScoreStatusToSkipTrainingSemantics() {
        RiskDecision d = evaluate(new StatusAnnotatingScorer(0.15, EvaluationStatus.INVALID_SCORE));
        assertThat(d.evaluationStatuses()).contains(EvaluationStatus.DEGRADED);
        assertThat(d.evaluationStatuses()).doesNotContain(EvaluationStatus.INVALID_SCORE);
        assertThat(d.anomalyScore()).isEqualTo(0.15);
        assertThat(d.action()).isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void contributorCannotForceWarmupActionForNonStatisticalScorer() {
        RiskDecision d = evaluate(new StatusAnnotatingScorer(0.9, EvaluationStatus.STATISTICAL_WARMUP));
        assertThat(d.evaluationStatuses()).contains(EvaluationStatus.DEGRADED);
        assertThat(d.evaluationStatuses()).doesNotContain(EvaluationStatus.STATISTICAL_WARMUP);
        assertThat(d.action()).isEqualTo(EnforcementAction.QUARANTINE);
    }

    private static RiskDecision evaluate(AnomalyScorer scorer) {
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQ.INSTANCE,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
        return engine.evaluate(
            new MapHttpRequestView().requestUri("/e").method("GET"),
            "id",
            FEATURES,
            new RequestContext());
    }

    private static class FixedScoreScorer implements AnomalyScorer {
        private final double score;

        FixedScoreScorer(double score) {
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

    private static class StatusAnnotatingScorer implements AnomalyScorer, EvaluationStatusContributor {
        private final double score;
        private final EvaluationStatus status;

        StatusAnnotatingScorer(double score, EvaluationStatus status) {
            this.score = score;
            this.status = status;
        }

        @Override
        public double score(RequestFeatures features) {
            return score;
        }

        @Override
        public void update(RequestFeatures features) {
        }

        @Override
        public void contributeEvaluationStatuses(RequestFeatures features,
                                                  EvaluationStatusContributionContext context) {
            context.add(status);
        }
    }

    private static final class ThrowingStatusContributorScorer implements AnomalyScorer, EvaluationStatusContributor {
        private final double score;

        ThrowingStatusContributorScorer(double score) {
            this.score = score;
        }

        @Override
        public double score(RequestFeatures features) {
            return score;
        }

        @Override
        public void update(RequestFeatures features) {
        }

        @Override
        public void contributeEvaluationStatuses(RequestFeatures features,
                                                  EvaluationStatusContributionContext context) {
            throw new IllegalStateException("contributor boom");
        }
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
