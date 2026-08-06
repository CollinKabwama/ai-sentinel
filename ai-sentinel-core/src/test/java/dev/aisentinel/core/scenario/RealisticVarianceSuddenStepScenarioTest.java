package dev.aisentinel.core.scenario;

import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.MIN_STD;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.THRESHOLD_ELEVATED;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.THRESHOLD_MODERATE;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.baseFeatures;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.newEngine;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.newStatisticalScorer;
import static dev.aisentinel.core.scenario.ScenarioTestSupport.shell;

/**
 * Scenario {@code realistic-variance-sudden-step}: non-constant calm baselines, then a rate step.
 * Controlled features → real scorer/engine/policy (not full extractor E2E).
 */
class RealisticVarianceSuddenStepScenarioTest {

    private static final String IDENTITY = "id-var-step";
    private static final String ENDPOINT = "/api/checkout";
    private static final double STEP_RPW = 100.0;
    private static final int ELEVATED = 25;

    private static final Set<EnforcementAction> THROTTLE_PLUS = EnumSet.of(
        EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);

    @Test
    void mildAndWideVarianceSteps_areMeasured() {
        StepResult mild = runStep(new double[] {9, 10, 11, 10, 9, 11, 10}, 8, 10.0, "mild-9-11");
        StepResult wide = runStep(new double[] {1, 50, 1, 50, 1, 50, 25}, 10, 25.0, "wide-1-50");

        System.out.printf(Locale.ROOT,
            "realistic-variance compare mildFirst=%.6f mildAction=%s mildStd=%.4f mildThrottle+=%d "
                + "wideFirst=%.6f wideAction=%s wideStd=%.4f wideThrottle+=%d "
                + "constantRefFirst=1.0 constantRefThrottle+=12%n",
            mild.firstScore, mild.firstAction, mild.std, mild.throttlePlus,
            wide.firstScore, wide.firstAction, wide.std, wide.throttlePlus);

        // Mild variance (std ~0.8) still saturates at 1.0 for a 10→100 step — not solely a MIN_STD artifact.
        assertThat(mild.std).isGreaterThan(MIN_STD);
        assertThat(mild.firstScore).isEqualTo(1.0);
        assertThat(mild.firstAction).isEqualTo(EnforcementAction.QUARANTINE);

        // Wide oscillating baseline: first-step score must remain THROTTLE+ but should not exceed mild.
        assertThat(wide.std).isGreaterThan(mild.std);
        assertThat(wide.firstScore).isGreaterThan(THRESHOLD_ELEVATED);
        assertThat(THROTTLE_PLUS.contains(wide.firstAction)).isTrue();
        assertThat(wide.firstScore).isLessThanOrEqualTo(mild.firstScore);

        // Default baseline-update gating: THROTTLE+ steps do not train, so scores hold.
        assertThat(mild.lastScore).isEqualTo(mild.firstScore);
        assertThat(wide.lastScore).isEqualTo(wide.firstScore);
    }

    private static StepResult runStep(double[] pattern, int repeats, double calmProbeRpw, String label) {
        StatisticalScorer scorer = newStatisticalScorer();
        SentinelDecisionEngine engine = newEngine(scorer);
        HttpRequestView request = shell(ENDPOINT);

        List<Double> baselineRpw = new ArrayList<>();
        for (int r = 0; r < repeats; r++) {
            for (double v : pattern) {
                baselineRpw.add(v);
                scorer.update(baseFeatures(IDENTITY + "-" + label, ENDPOINT, v));
            }
        }
        double mean = baselineRpw.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double var = 0;
        for (double v : baselineRpw) {
            double d = v - mean;
            var += d * d;
        }
        double std = Math.sqrt(var / (baselineRpw.size() - 1));
        double stdFloor = Math.max(std, MIN_STD);

        String id = IDENTITY + "-" + label;
        RiskDecision calm = engine.evaluate(request, id,
            baseFeatures(id, ENDPOINT, calmProbeRpw), new RequestContext());
        assertThat(calm).isNotNull();

        RequestFeatures stepFeat = baseFeatures(id, ENDPOINT, STEP_RPW);
        List<Row> elevated = new ArrayList<>(ELEVATED);
        for (int i = 0; i < ELEVATED; i++) {
            RiskDecision d = engine.evaluate(request, id, stepFeat, new RequestContext());
            assertThat(d).isNotNull();
            double z = i == 0 ? Math.abs((STEP_RPW - mean) / stdFloor) : Double.NaN;
            elevated.add(new Row(i + 1, d.anomalyScore(), d.action(), z));
        }

        Row first = elevated.get(0);
        Row last = elevated.get(elevated.size() - 1);
        int throttlePlus = 0;
        Integer firstBelowThrottle = null;
        Integer firstBelowMonitor = null;
        for (Row row : elevated) {
            if (THROTTLE_PLUS.contains(row.action)) {
                throttlePlus++;
            } else if (firstBelowThrottle == null) {
                firstBelowThrottle = row.sample;
            }
            if (row.score < THRESHOLD_MODERATE && firstBelowMonitor == null) {
                firstBelowMonitor = row.sample;
            }
        }

        System.out.printf(Locale.ROOT,
            "realistic-variance-sudden-step label=%s mean=%.4f std=%.4f calmScore=%.6f calmAction=%s "
                + "first=%.6f action=%s z0=%.3f throttle+=%d belowThrottle=%s belowMonitor=%s last=%.6f%n",
            label, mean, std, calm.anomalyScore(), calm.action(),
            first.score, first.action, first.z, throttlePlus, firstBelowThrottle, firstBelowMonitor, last.score);

        return new StepResult(mean, std, calm.anomalyScore(), first.score, first.action,
            throttlePlus, firstBelowThrottle, firstBelowMonitor, last.score);
    }

    private record Row(int sample, double score, EnforcementAction action, double z) {}

    private record StepResult(
        double mean, double std, double calmScore,
        double firstScore, EnforcementAction firstAction,
        int throttlePlus, Integer firstBelowThrottle, Integer firstBelowMonitor, double lastScore
    ) {}
}
