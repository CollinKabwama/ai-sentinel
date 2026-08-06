package dev.aisentinel.core.scenario;

import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.scenario.DetectionScenarioRunner.DetectionScenario;
import dev.aisentinel.core.scenario.DetectionScenarioRunner.DetectionScenarioStep;
import dev.aisentinel.core.scenario.DetectionScenarioRunner.ScenarioObservation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario {@code normal-established-baseline}: stable synthetic traffic through the real extractor path.
 * Characterization only — does not require ALLOW.
 */
class NormalEstablishedBaselineScenarioTest {

    private static final String SCENARIO_ID = "normal-established-baseline";
    private static final String IDENTITY = "id-benign";
    private static final String ENDPOINT = "/api/hello";
    private static final int TOTAL = 60;

    @Test
    void benignTraffic_beyondWarmup_isDeterministic() {
        List<ScenarioObservation> first = runOnce();
        List<ScenarioObservation> second = runOnce();

        assertThat(first).hasSize(TOTAL);
        assertThat(second).hasSize(TOTAL);

        // Warmup ends after 2 samples (score leaves fixed 0.4).
        assertThat(first.get(0).anomalyScore()).isEqualTo(ScenarioTestSupport.WARMUP_SCORE);
        assertThat(first.get(1).anomalyScore()).isEqualTo(ScenarioTestSupport.WARMUP_SCORE);
        assertThat(first.get(2).anomalyScore()).isNotEqualTo(ScenarioTestSupport.WARMUP_SCORE);

        ScenarioObservation last = first.get(TOTAL - 1);
        for (int i = 0; i < TOTAL; i++) {
            assertThat(second.get(i).anomalyScore()).isEqualTo(first.get(i).anomalyScore());
            assertThat(second.get(i).action()).isEqualTo(first.get(i).action());
            assertThat(second.get(i).features().headerFingerprintHash())
                .isEqualTo(first.get(i).features().headerFingerprintHash());
            assertThat(second.get(i).features().ipBucket())
                .isEqualTo(first.get(i).features().ipBucket());
            assertThat(second.get(i).features().parameterCount()).isEqualTo(0);
            assertThat(second.get(i).features().payloadSizeBytes()).isEqualTo(0L);
            assertThat(second.get(i).features().tokenAgeSeconds()).isEqualTo(-1.0);
        }

        long allowCount = first.stream().filter(o -> o.action() == EnforcementAction.ALLOW).count();
        long belowModerate = first.stream().filter(o -> o.anomalyScore() < ScenarioTestSupport.THRESHOLD_MODERATE).count();
        double minPostWarmup = first.stream().skip(2).mapToDouble(ScenarioObservation::anomalyScore).min().orElseThrow();
        double maxPostWarmup = first.stream().skip(2).mapToDouble(ScenarioObservation::anomalyScore).max().orElseThrow();

        System.out.printf(Locale.ROOT,
            "%s summary lastRpw=%.0f lastScore=%.6f lastAction=%s allowCount=%d below0.2Count=%d "
                + "minPostWarmup=%.6f maxPostWarmup=%.6f%n",
            SCENARIO_ID, last.features().requestsPerWindow(), last.anomalyScore(), last.action(),
            allowCount, belowModerate, minPostWarmup, maxPostWarmup);

        // Explicit recorded characterization (do not force ALLOW).
        assertThat(last.startupGraceActive()).isFalse();
        assertThat(last.policyScore()).isEqualTo(last.anomalyScore());
        assertThat(last.features().requestsPerWindow()).isEqualTo(TOTAL);
    }

    private static List<ScenarioObservation> runOnce() {
        DetectionScenarioRunner runner = DetectionScenarioRunner.statisticalOnly();
        List<DetectionScenarioStep> steps = new ArrayList<>(TOTAL);
        for (int i = 0; i < TOTAL; i++) {
            steps.add(new DetectionScenarioStep(IDENTITY, stableRequest()));
        }
        return runner.run(new DetectionScenario(SCENARIO_ID, List.copyOf(steps)));
    }

    private static MapHttpRequestView stableRequest() {
        return new MapHttpRequestView()
            .requestUri(ENDPOINT)
            .method("GET")
            .remoteAddr("203.0.113.60")
            .header("Accept", "application/json");
    }
}
