package dev.aisentinel.core.enforcement;

import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MonitorOnlyEnforcementHandlerTest {

    @Test
    void isQuarantinedDelegatesToDelegate() {
        EnforcementHandler delegate = mock(EnforcementHandler.class);
        TelemetryEmitter telemetry = mock(TelemetryEmitter.class);
        when(delegate.isQuarantined("h1", "")).thenReturn(true);
        when(delegate.isQuarantined("h2", "")).thenReturn(false);

        MonitorOnlyEnforcementHandler handler = new MonitorOnlyEnforcementHandler(delegate, telemetry);

        assertThat(handler.isQuarantined("h1")).isTrue();
        assertThat(handler.isQuarantined("h2")).isFalse();
        verify(delegate).isQuarantined("h1", "");
        verify(delegate).isQuarantined("h2", "");
    }
}
