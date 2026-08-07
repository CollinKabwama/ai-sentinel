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
import static dev.aisentinel.core.scenario.ScenarioTestSupport.THRESHOLD_CRITICAL;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.features;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.newEngine;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.newStatisticalScorer;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.shell;

/**
 * Regression for near-zero-variance / identity-like feature false positives.
 * Constant baseline then one-dimension probe through real scorer/engine/policy.
 */
class NearZeroVarianceSensitivityTest {

    private static final String IDENTITY = "id-nzv";
    private static final String ENDPOINT = "/api/checkout";
    private static final int BASELINE_N = 40;

    @Test
    void constantFeatureThenMinimalChange_noLongerCatastrophic() {
        Result params = probe("parameterCount",
            base(0, 0L, 42L, 7),
            base(1, 0L, 42L, 7));

        Result payloadSmall = probe("payloadSize_1",
            base(0, 0L, 42L, 7),
            base(0, 1L, 42L, 7));

        Result payloadLarge = probe("payloadSize_1024",
            base(0, 0L, 42L, 7),
            base(0, 1024L, 42L, 7));

        Result header = probe("headerFingerprint",
            base(0, 0L, 42L, 7),
            base(0, 0L, 43L, 7));

        Result ip = probe("ipBucket",
            base(0, 0L, 42L, 7),
            base(0, 0L, 42L, 8));

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
        RiskDecision mildStep = controlEngine.evaluate(req, IDENTITY,
            features(IDENTITY, ENDPOINT, 12, 0.1, -1, 0, 0L, 42L, 7), new RequestContext());
        RiskDecision genuineBurst = controlEngine.evaluate(req, IDENTITY,
            features(IDENTITY, ENDPOINT, 100, 0.1, -1, 0, 0L, 42L, 7), new RequestContext());

        System.out.printf(Locale.ROOT,
            "near-zero-variance matrix feature,probeScore,probeAction%n");
        for (Result r : new Result[] {params, payloadSmall, payloadLarge, header, ip}) {
            System.out.printf(Locale.ROOT, "%s,%.6f,%s%n", r.name, r.score, r.action);
        }
        System.out.printf(Locale.ROOT,
            "control_rpw calm=%.6f mildStep=%.6f genuineBurst=%.6f%n",
            calmCtrl.anomalyScore(), mildStep.anomalyScore(), genuineBurst.anomalyScore());

        // Tiny flips must not immediately saturate / QUARANTINE.
        assertThat(params.score).isLessThan(THRESHOLD_CRITICAL);
        assertThat(payloadSmall.score).isLessThan(THRESHOLD_CRITICAL);
        assertThat(params.action).isNotEqualTo(EnforcementAction.QUARANTINE);
        assertThat(payloadSmall.action).isNotEqualTo(EnforcementAction.QUARANTINE);

        // Identity-like hash/IP excluded from statistical scoring → near-zero effect.
        assertThat(header.score).isLessThan(0.1);
        assertThat(ip.score).isLessThan(0.1);
        assertThat(header.action).isIn(EnforcementAction.ALLOW, EnforcementAction.MONITOR);
        assertThat(ip.action).isIn(EnforcementAction.ALLOW, EnforcementAction.MONITOR);

        // Capped ordinal/magnitude: large payload jump elevated vs unit flip, still not floor-saturated 1.0.
        assertThat(payloadLarge.score).isGreaterThan(payloadSmall.score);
        assertThat(payloadLarge.score).isLessThan(1.0);

        // Genuine rate anomaly remains significantly hotter than tiny feature flips.
        assertThat(genuineBurst.anomalyScore()).isGreaterThan(params.score);
        assertThat(genuineBurst.anomalyScore()).isGreaterThan(payloadSmall.score);
        assertThat(genuineBurst.anomalyScore()).isGreaterThan(header.score);
        assertThat(genuineBurst.anomalyScore()).isGreaterThanOrEqualTo(THRESHOLD_CRITICAL);
        assertThat(mildStep.anomalyScore()).isGreaterThan(calmCtrl.anomalyScore());
    }

    private static RequestFeatures base(int params, long payload, long headerFp, int ip) {
        return features(IDENTITY, ENDPOINT, 10.0, 0.1, -1, params, payload, headerFp, ip);
    }

    private static Result probe(String name, RequestFeatures baseline, RequestFeatures probeFeatures) {
        StatisticalScorer scorer = newStatisticalScorer();
        SentinelDecisionEngine engine = newEngine(scorer);
        HttpRequestView request = shell(ENDPOINT);
        for (int i = 0; i < BASELINE_N; i++) {
            scorer.update(baseline);
        }
        RiskDecision d = engine.evaluate(request, IDENTITY, probeFeatures, new RequestContext());
        assertThat(d).isNotNull();
        return new Result(name, d.anomalyScore(), d.action());
    }

    private record Result(String name, double score, EnforcementAction action) {}
}
