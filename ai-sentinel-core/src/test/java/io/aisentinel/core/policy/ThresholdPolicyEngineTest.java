package io.aisentinel.core.policy;

import io.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThresholdPolicyEngineTest {

    private static RequestFeatures dummyFeatures() {
        return RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }

    @Test
    void evaluateMapsScoreToAction() {
        var engine = new ThresholdPolicyEngine();
        var f = dummyFeatures();

        assertThat(engine.evaluate(0.1, f, "/api")).isEqualTo(EnforcementAction.ALLOW);
        assertThat(engine.evaluate(0.3, f, "/api")).isEqualTo(EnforcementAction.MONITOR);
        assertThat(engine.evaluate(0.5, f, "/api")).isEqualTo(EnforcementAction.THROTTLE);
        assertThat(engine.evaluate(0.7, f, "/api")).isEqualTo(EnforcementAction.BLOCK);
        assertThat(engine.evaluate(0.9, f, "/api")).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void customThresholdsChangeBands() {
        var engine = new ThresholdPolicyEngine(0.1, 0.3, 0.5, 0.7);
        var f = dummyFeatures();
        assertThat(engine.evaluate(0.05, f, "/api")).isEqualTo(EnforcementAction.ALLOW);
        assertThat(engine.evaluate(0.1, f, "/api")).isEqualTo(EnforcementAction.MONITOR);
        assertThat(engine.evaluate(0.35, f, "/api")).isEqualTo(EnforcementAction.THROTTLE);
        assertThat(engine.evaluate(0.55, f, "/api")).isEqualTo(EnforcementAction.BLOCK);
        assertThat(engine.evaluate(0.75, f, "/api")).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void validateThresholdsRejectsNonAscendingOrder() {
        assertThatThrownBy(() -> new ThresholdPolicyEngine(0.2, 0.2, 0.6, 0.8))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold-moderate");
    }

    @Test
    void validateThresholdsRejectsOutOfRange() {
        assertThatThrownBy(() -> new ThresholdPolicyEngine(-0.1, 0.4, 0.6, 0.8))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("[0.0, 1.0]");
        assertThatThrownBy(() -> new ThresholdPolicyEngine(0.1, 0.2, 0.3, 1.1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("[0.0, 1.0]");
    }
}
