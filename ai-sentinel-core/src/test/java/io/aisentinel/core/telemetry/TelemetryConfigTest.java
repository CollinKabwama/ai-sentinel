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
    void recordConstructionPreservesFields() {
        TelemetryConfig c = new TelemetryConfig(TelemetryConfig.LogVerbosity.NONE, 0.7, 10);
        assertThat(c.logVerbosity()).isEqualTo(TelemetryConfig.LogVerbosity.NONE);
        assertThat(c.logScoreThreshold()).isEqualTo(0.7);
        assertThat(c.logSampleRate()).isEqualTo(10);
    }
}
