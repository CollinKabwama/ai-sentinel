package io.aisentinel.core.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryConfigTest {

    @Test
    void defaultsUseFullVerbosityAndSampleRate() {
        TelemetryConfig d = TelemetryConfig.defaults();
        assertThat(d.logVerbosity()).isEqualTo(TelemetryConfig.LogVerbosity.FULL);
        assertThat(d.logScoreThreshold()).isEqualTo(0.4);
        assertThat(d.logSampleRate()).isEqualTo(100);
    }

    @Test
    void sampledRecordHoldsConfiguredValues() {
        TelemetryConfig c = new TelemetryConfig(TelemetryConfig.LogVerbosity.SAMPLED, 0.72, 25);
        assertThat(c.logVerbosity()).isEqualTo(TelemetryConfig.LogVerbosity.SAMPLED);
        assertThat(c.logScoreThreshold()).isEqualTo(0.72);
        assertThat(c.logSampleRate()).isEqualTo(25);
    }

    @Test
    void noneRecordPreservesFields() {
        TelemetryConfig c = new TelemetryConfig(TelemetryConfig.LogVerbosity.NONE, 0.7, 10);
        assertThat(c.logVerbosity()).isEqualTo(TelemetryConfig.LogVerbosity.NONE);
        assertThat(c.logScoreThreshold()).isEqualTo(0.7);
        assertThat(c.logSampleRate()).isEqualTo(10);
    }
}
