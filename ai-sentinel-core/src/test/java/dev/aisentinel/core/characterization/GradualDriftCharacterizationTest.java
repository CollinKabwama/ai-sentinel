package dev.aisentinel.core.characterization;

import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
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
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes gradual drift and low-and-slow traffic against Welford scoring with baseline-update gating.
 * Documents observed asymptotes; does not introduce alternate detectors.
 */
class GradualDriftCharacterizationTest {

    private static final String ID = "char-gradual";
    private static final String EP = "/api/resource";

    @Test
    void verySlowLinearRamp_underGating_lateScoreStaysBelowThrottle() {
        RampResult gated = runLinearRamp(ConfigurableBaselineUpdatePolicy.allowOrMonitor(), 0.05, 200);
        RampResult always = runLinearRamp(ConfigurableBaselineUpdatePolicy.always(), 0.05, 200);

        System.out.printf(Locale.ROOT,
            "slow-ramp gated mid=%.4f late=%.4f max=%.4f actions=%s | always mid=%.4f late=%.4f max=%.4f%n",
            gated.midScore, gated.lateScore, gated.maxScore, gated.actionsSummary,
            always.midScore, always.lateScore, always.maxScore);

        // Under ALLOW_OR_MONITOR, slow ramp should not silently train to erase late elevation as thoroughly as ALWAYS.
        assertThat(gated.lateScore)
            .as("gated late score should remain detectable vs early calm")
            .isGreaterThan(gated.earlyCalmScore);
        // Continuous learning under ALWAYS often compresses late elevation on gradual ramps.
        assertThat(always.lateScore)
            .as("ALWAYS path often compresses gradual ramps")
            .isLessThan(0.5);
    }

    @Test
    void moderateLinearRamp_gatingKeepsLateElevatedRelativeToAlways() {
        RampResult gated = runLinearRamp(ConfigurableBaselineUpdatePolicy.allowOrMonitor(), 0.5, 80);
        RampResult always = runLinearRamp(ConfigurableBaselineUpdatePolicy.always(), 0.5, 80);

        System.out.printf(Locale.ROOT,
            "moderate-ramp gated late=%.4f always late=%.4f gatedMaxAction=%s alwaysMaxAction=%s%n",
            gated.lateScore, always.lateScore, gated.maxAction, always.maxAction);

        assertThat(gated.lateScore)
            .as("gating should preserve more late elevation than unconditional learning on moderate ramp")
            .isGreaterThanOrEqualTo(always.lateScore - 0.05);
    }

    @Test
    void stairStepGrowth_underGating_currentBehaviorRemainsElevatedOverTestedPlateau() {
        // Observational characterization of CURRENT adaptation under ALLOW_OR_MONITOR vs ALWAYS.
        // Not a product guarantee that permanent elevation is desirable — recovery/relearn policy is
        // an architecture decision (explicit BaselineLifecycle reset exists; automatic relearn does not).
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine gated = engine(scorer, ConfigurableBaselineUpdatePolicy.allowOrMonitor());
        StatisticalScorer alwaysScorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine always = engine(alwaysScorer, ConfigurableBaselineUpdatePolicy.always());
        HttpRequestView req = shell();

        for (int i = 0; i < 30; i++) {
            gated.evaluate(req, ID, feat(10.0), new RequestContext());
            always.evaluate(req, ID + "-a", feat(10.0), new RequestContext());
        }
        List<Double> gatedScores = new ArrayList<>();
        List<Double> alwaysScores = new ArrayList<>();
        for (double rpw : new double[] {20, 20, 40, 40, 40, 40, 40, 40, 40, 40}) {
            gatedScores.add(gated.evaluate(req, ID, feat(rpw), new RequestContext()).anomalyScore());
            alwaysScores.add(always.evaluate(req, ID + "-a", feat(rpw), new RequestContext()).anomalyScore());
        }
        double gatedLate = gatedScores.get(gatedScores.size() - 1);
        double alwaysLate = alwaysScores.get(alwaysScores.size() - 1);
        System.out.printf(Locale.ROOT,
            "stair (current behavior) gatedLate=%.4f alwaysLate=%.4f gated=%s always=%s%n",
            gatedLate, alwaysLate, gatedScores, alwaysScores);

        assertThat(gatedScores.get(0))
            .as("first step remains detectable vs calm baseline")
            .isGreaterThan(0.2);
        // Measured current behavior over this finite plateau horizon (not an ENFORCE contract).
        assertThat(gatedLate)
            .as("current gated behavior remains elevated for the tested plateau horizon")
            .isGreaterThan(0.9);
        assertThat(alwaysLate)
            .as("ALWAYS relearning compresses late plateau relative to gated freeze")
            .isLessThanOrEqualTo(gatedLate);
        assertThat(Double.isFinite(gatedLate)).isTrue();
        assertThat(Double.isFinite(alwaysLate)).isTrue();
    }

    @Test
    void lowAndSlow_alternatingNormalAndSlightlyElevated_doesNotSaturate() {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = engine(scorer, ConfigurableBaselineUpdatePolicy.allowOrMonitor());
        HttpRequestView req = shell();

        for (int i = 0; i < 40; i++) {
            engine.evaluate(req, ID + "-als", feat(10.0), new RequestContext());
        }
        double max = 0;
        for (int i = 0; i < 60; i++) {
            double rpw = (i % 2 == 0) ? 10.0 : 14.0;
            RiskDecision d = engine.evaluate(req, ID + "-als", feat(rpw), new RequestContext());
            max = Math.max(max, d.anomalyScore());
        }
        System.out.printf(Locale.ROOT, "low-and-slow maxScore=%.4f%n", max);
        assertThat(max).isLessThan(0.8);
    }

    private static RampResult runLinearRamp(ConfigurableBaselineUpdatePolicy policy, double slope, int steps) {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = engine(scorer, policy);
        HttpRequestView req = shell();
        for (int i = 0; i < 20; i++) {
            engine.evaluate(req, ID, feat(10.0), new RequestContext());
        }
        RiskDecision calm = engine.evaluate(req, ID, feat(10.0), new RequestContext());
        double mid = 0;
        double late = 0;
        double max = 0;
        EnforcementAction maxAction = EnforcementAction.ALLOW;
        StringBuilder actions = new StringBuilder();
        for (int i = 1; i <= steps; i++) {
            double rpw = 10.0 + slope * i;
            RiskDecision d = engine.evaluate(req, ID, feat(rpw), new RequestContext());
            max = Math.max(max, d.anomalyScore());
            if (d.action().ordinal() > maxAction.ordinal()) {
                maxAction = d.action();
            }
            if (i == steps / 2) {
                mid = d.anomalyScore();
            }
            if (i == steps) {
                late = d.anomalyScore();
                actions.append(d.action());
            }
        }
        return new RampResult(calm.anomalyScore(), mid, late, max, maxAction, actions.toString());
    }

    private static SentinelDecisionEngine engine(StatisticalScorer scorer, ConfigurableBaselineUpdatePolicy policy) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQ.INSTANCE,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            policy
        );
    }

    private static RequestFeatures feat(double rpw) {
        return RequestFeatures.builder()
            .identityHash(ID)
            .endpoint(EP)
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(rpw)
            .endpointEntropy(0.1)
            .endpointConcentration(0.9)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(42L)
            .ipBucket(7)
            .build();
    }

    private static HttpRequestView shell() {
        return new MapHttpRequestView().requestUri(EP).method("GET");
    }

    private record RampResult(double earlyCalmScore, double midScore, double lateScore, double maxScore,
                              EnforcementAction maxAction, String actionsSummary) {
    }

    private enum NeverQ implements EnforcementHandler {
        INSTANCE;

        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            return true;
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }
}
