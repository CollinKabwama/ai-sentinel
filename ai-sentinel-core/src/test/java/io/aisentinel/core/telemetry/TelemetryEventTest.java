package io.aisentinel.core.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryEventTest {

    @Test
    void threatScoredMasksShortHash() {
        TelemetryEvent e = TelemetryEvent.threatScored("short", "/api", 0.5);
        assertThat(e.type()).isEqualTo("ThreatScored");
        assertThat(e.payload().get("identityHash")).isEqualTo("***");
        assertThat(e.payload().get("endpoint")).isEqualTo("/api");
        assertThat(e.payload().get("score")).isEqualTo(0.5);
        assertThat(e.timestampMillis()).isPositive();
    }

    @Test
    void threatScoredMasksLongHash() {
        TelemetryEvent e = TelemetryEvent.threatScored("abcdefgh_ijklmnop", "/x", 0.0);
        assertThat(e.payload().get("identityHash")).isEqualTo("abcd***mnop");
    }

    @Test
    void policyActionAppliedOmitsDetailWhenNull() {
        TelemetryEvent e = TelemetryEvent.policyActionApplied("abcdefgh_ijklmnop", "/p", "BLOCK", null);
        assertThat(e.type()).isEqualTo("PolicyActionApplied");
        assertThat(e.payload()).doesNotContainKey("detail");
        assertThat(e.payload().get("action")).isEqualTo("BLOCK");
    }

    @Test
    void policyActionAppliedIncludesDetailWhenPresent() {
        TelemetryEvent e = TelemetryEvent.policyActionApplied("abcdefgh_ijklmnop", "/p", "MONITOR", "reason");
        assertThat(e.payload().get("detail")).isEqualTo("reason");
    }

    @Test
    void anomalyDetectedAndQuarantineStartedUseMaskedHash() {
        TelemetryEvent a = TelemetryEvent.anomalyDetected("12345678", "/", 0.9);
        assertThat(a.payload().get("identityHash")).isEqualTo("1234***5678");

        TelemetryEvent q = TelemetryEvent.quarantineStarted("12345678", "/", 60_000L);
        assertThat(q.type()).isEqualTo("QuarantineStarted");
        assertThat(q.payload().get("durationMs")).isEqualTo(60_000L);
    }
}
