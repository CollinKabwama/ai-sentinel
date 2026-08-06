package dev.aisentinel.core.scenario;

import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.scenario.DetectionScenarioRunner.DetectionScenario;
import dev.aisentinel.core.scenario.DetectionScenarioRunner.DetectionScenarioStep;
import dev.aisentinel.core.scenario.DetectionScenarioRunner.ScenarioObservation;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gradual linear request-rate ramp.
 * <p>
 * Historically this scenario was called a “rapid burst”; the class was renamed because the
 * extractor produces {@code requestsPerWindow = 1,2,3,…,N} (a linear ramp), not a true step.
 * Scenario id {@code rapid-endpoint-request-burst} is retained only for log continuity.
 * <p>
 * Finding preserved: a late linear-ramp request is not scored as riskier than a mid-ramp probe
 * under continuous online {@code score()} then {@code update()}.
 */
class GradualEndpointRequestRampScenarioTest {

    private static final String SCENARIO_ID = "rapid-endpoint-request-burst";
    private static final String IDENTITY = "id-burst";
    private static final String ENDPOINT = "/api/checkout";
    private static final String REMOTE = "203.0.113.40";
    private static final String ACCEPT = "application/json";

    private static final int WARMUP_REQUESTS = 2;
    private static final int CALM_POST_WARMUP = 8;
    private static final int CALM_TOTAL = WARMUP_REQUESTS + CALM_POST_WARMUP;
    private static final int RAMP_EXTRA = 80;
    private static final double WARMUP_SCORE = 0.4;

    @Test
    void gradualRamp_lateProbeNotRiskierThanMidRamp_underOnlineUpdates() {
        DetectionScenarioRunner runner = DetectionScenarioRunner.statisticalOnly();
        assertThat(runner.scorer()).isInstanceOf(StatisticalScorer.class);

        List<DetectionScenarioStep> steps = new ArrayList<>(CALM_TOTAL + RAMP_EXTRA);
        for (int i = 0; i < CALM_TOTAL + RAMP_EXTRA; i++) {
            steps.add(new DetectionScenarioStep(IDENTITY, stableRequest()));
        }

        List<ScenarioObservation> obs = runner.run(new DetectionScenario(SCENARIO_ID, List.copyOf(steps)));
        assertThat(obs).hasSize(CALM_TOTAL + RAMP_EXTRA);

        assertThat(obs.get(0).anomalyScore()).isEqualTo(WARMUP_SCORE);
        assertThat(obs.get(1).anomalyScore()).isEqualTo(WARMUP_SCORE);
        assertThat(obs.get(0).action()).isEqualTo(EnforcementAction.THROTTLE);

        ScenarioObservation firstLive = obs.get(WARMUP_REQUESTS);
        ScenarioObservation midRamp = obs.get(CALM_TOTAL - 1);
        ScenarioObservation lateRamp = obs.get(obs.size() - 1);

        assertThat(firstLive.anomalyScore()).isNotEqualTo(WARMUP_SCORE);
        assertThat(midRamp.anomalyScore()).isNotEqualTo(WARMUP_SCORE);

        assertThat(lateRamp.features().requestsPerWindow())
            .isGreaterThan(midRamp.features().requestsPerWindow());
        assertThat(lateRamp.features().headerFingerprintHash())
            .isEqualTo(midRamp.features().headerFingerprintHash());
        assertThat(lateRamp.features().ipBucket()).isEqualTo(midRamp.features().ipBucket());
        assertThat(lateRamp.policyScore()).isEqualTo(lateRamp.anomalyScore());
        assertThat(lateRamp.startupGraceActive()).isFalse();

        boolean scoreIncreased = lateRamp.anomalyScore() > midRamp.anomalyScore();
        boolean throttleOrStricter = lateRamp.action() == EnforcementAction.THROTTLE
            || lateRamp.action() == EnforcementAction.BLOCK
            || lateRamp.action() == EnforcementAction.QUARANTINE;

        String evidence = String.format(Locale.ROOT,
            "scenario=%s midRpw=%.0f midScore=%.6f midAction=%s lateRpw=%.0f lateScore=%.6f lateAction=%s",
            SCENARIO_ID,
            midRamp.features().requestsPerWindow(), midRamp.anomalyScore(), midRamp.action(),
            lateRamp.features().requestsPerWindow(), lateRamp.anomalyScore(), lateRamp.action());

        assertThat(lateRamp.anomalyScore())
            .as("late linear ramp not riskier than mid-ramp. %s", evidence)
            .isLessThanOrEqualTo(midRamp.anomalyScore());
        assertThat(lateRamp.action())
            .as("late ramp below THROTTLE. %s", evidence)
            .isIn(EnforcementAction.ALLOW, EnforcementAction.MONITOR);
        assertThat(scoreIncreased && throttleOrStricter)
            .as("sudden-burst product claim NOT confirmed for gradual ramp. %s", evidence)
            .isFalse();
    }

    private static MapHttpRequestView stableRequest() {
        return new MapHttpRequestView()
            .requestUri(ENDPOINT)
            .method("GET")
            .remoteAddr(REMOTE)
            .header("Accept", ACCEPT);
    }
}
