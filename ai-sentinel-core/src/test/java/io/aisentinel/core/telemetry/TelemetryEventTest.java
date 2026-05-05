package io.aisentinel.core.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryEventTest {

    @Test
    void threatScoredMasksShortHash() {
        TelemetryEvent e = TelemetryEvent.threatScored("abc", "/x", 0.5);
        assertThat(e.type()).isEqualTo("ThreatScored");
        assertThat(e.payload().get("identityHash")).isEqualTo("***");
        assertThat(e.payload().get("endpoint")).isEqualTo("/x");
        assertThat(e.payload().get("score")).isEqualTo(0.5);
    }

    @Test
    void threatScoredMasksLongHash() {
        TelemetryEvent e = TelemetryEvent.threatScored("1234567890abcdef", "/api", 0.1);
        assertThat(e.payload().get("identityHash")).isEqualTo("1234***cdef");
    }

    @Test
    void policyActionAppliedOmitsNullDetail() {
        TelemetryEvent e = TelemetryEvent.policyActionApplied("12345678", "/p", "ALLOW", null);
        assertThat(e.payload()).doesNotContainKey("detail");
    }

    @Test
    void policyActionAppliedIncludesDetailWhenPresent() {
        TelemetryEvent e = TelemetryEvent.policyActionApplied("12345678", "/p", "BLOCK", "403");
        assertThat(e.payload().get("detail")).isEqualTo("403");
    }
}
