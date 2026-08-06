package dev.aisentinel.core.scenario;

import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.MIN_STD;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.features;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.newEngine;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.newStatisticalScorer;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.shell;

/**
 * Near-zero-variance feature sensitivity: one dimension changes after a constant baseline.
 * Controlled features → real scorer/engine/policy. Characterization only.
 */
class NearZeroVarianceSensitivityTest {

    private static final String IDENTITY = "id-nzv";
    private static final String ENDPOINT = "/api/checkout";
    private static final int BASELINE_N = 40;

    @Test
    void constantFeatureThenMinimalChange_isMeasuredPerDimension() {
        Result params = probe("parameterCount",
            base(0, 0L, 42L, 7),
            base(1, 0L, 42L, 7),
            1.0, 0.0, MIN_STD);

        Result payloadSmall = probe("payloadSize_1",
            base(0, 0L, 42L, 7),
            base(0, 1L, 42L, 7),
            1.0, 0.0, MIN_STD);

        Result payloadLarge = probe("payloadSize_1024",
            base(0, 0L, 42L, 7),
            base(0, 1024L, 42L, 7),
            1024.0, 0.0, MIN_STD);

        Result header = probe("headerFingerprint",
            base(0, 0L, 42L, 7),
            base(0, 0L, 43L, 7),
            43.0, 42.0, MIN_STD);

        Result ip = probe("ipBucket",
            base(0, 0L, 42L, 7),
            base(0, 0L, 42L, 8),
            8.0, 7.0, MIN_STD);

        // Control: nonzero variance on rpw, then small change within band
        StatisticalScorer controlScorer = newStatisticalScorer();
        SentinelDecisionEngine controlEngine = newEngine(controlScorer);
        HttpRequestView req = shell(ENDPOINT);
        double[] rpwPattern = {9, 10, 11, 10, 9, 11, 10};
        for (int r = 0; r < 8; r++) {
            for (double v : rpwPattern) {
                controlScorer.update(features(IDENTITY, ENDPOINT, v, 0.1, -1, 0, 0L, 42L, 7));
            }
        }
        RiskDecision calmCtrl = controlEngine.evaluate(req, IDENTITY,
            features(IDENTITY, ENDPOINT, 10, 0.1, -1, 0, 0L, 42L, 7), new RequestContext());
        RiskDecision stepCtrl = controlEngine.evaluate(req, IDENTITY,
            features(IDENTITY, ENDPOINT, 12, 0.1, -1, 0, 0L, 42L, 7), new RequestContext());

        System.out.printf(Locale.ROOT,
            "near-zero-variance matrix feature,probeScore,probeAction,approxZ%n");
        for (Result r : new Result[] {params, payloadSmall, payloadLarge, header, ip}) {
            System.out.printf(Locale.ROOT, "%s,%.6f,%s,%.3f%n", r.name, r.score, r.action, r.approxZ);
        }
        System.out.printf(Locale.ROOT,
            "control_rpw_variance calmScore=%.6f calmAction=%s mildStepScore=%.6f mildStepAction=%s%n",
            calmCtrl.anomalyScore(), calmCtrl.action(), stepCtrl.anomalyScore(), stepCtrl.action());

        // Durable relationships: constant-baseline single-dim flips hit the std floor → extreme scores.
        assertThat(params.score).isEqualTo(1.0);
        assertThat(payloadSmall.score).isEqualTo(1.0);
        assertThat(payloadLarge.score).isEqualTo(1.0);
        assertThat(header.score).isEqualTo(1.0);
        assertThat(ip.score).isEqualTo(1.0);
        assertThat(params.action).isEqualTo(EnforcementAction.QUARANTINE);

        // With realistic rpw variance, a mild +2 step is below a MIN_STD-floor single-feature flip (1.0),
        // but can still land in a high band — measured here for the report.
        assertThat(stepCtrl.anomalyScore()).isLessThan(params.score);
        assertThat(stepCtrl.anomalyScore()).isGreaterThan(calmCtrl.anomalyScore());
        assertThat(params.approxZ).isGreaterThan(1_000);
    }

    private static RequestFeatures base(int params, long payload, long headerFp, int ip) {
        return features(IDENTITY, ENDPOINT, 10.0, 0.1, -1, params, payload, headerFp, ip);
    }

    private static Result probe(String name, RequestFeatures baseline, RequestFeatures probe,
                                double probeDim, double meanDim, double stdDim) {
        StatisticalScorer scorer = newStatisticalScorer();
        SentinelDecisionEngine engine = newEngine(scorer);
        HttpRequestView request = shell(ENDPOINT);
        for (int i = 0; i < BASELINE_N; i++) {
            scorer.update(baseline);
        }
        RiskDecision d = engine.evaluate(request, IDENTITY, probe, new RequestContext());
        assertThat(d).isNotNull();
        double z = Math.abs((probeDim - meanDim) / Math.max(stdDim, MIN_STD));
        return new Result(name, d.anomalyScore(), d.action(), z);
    }

    private record Result(String name, double score, EnforcementAction action, double approxZ) {}
}
