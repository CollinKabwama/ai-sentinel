package io.aisentinel.core.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryConfigTest {

    @Test
    void defaultsUseFullVerbosityAndPositiveSampleRate() {
        TelemetryConfig d = TelemetryConfig.defaults();
        assertThat(d.logVerbosity()).isEqualTo(TelemetryConfig.LogVerbosity.FULL);
        assertThat(d.logScoreThreshold()).isEqualTo(0.4);
        assertThat(d.logSampleRate()).isEqualTo(100);
    }

    @Test
    void logVerbosityEnumValues() {
        assertThat(TelemetryConfig.LogVerbosity.values())
            .containsExactly(
                TelemetryConfig.LogVerbosity.FULL,
                TelemetryConfig.LogVerbosity.ANOMALY_ONLY,
                TelemetryConfig.LogVerbosity.SAMPLED,
                TelemetryConfig.LogVerbosity.NONE);
    }
}
