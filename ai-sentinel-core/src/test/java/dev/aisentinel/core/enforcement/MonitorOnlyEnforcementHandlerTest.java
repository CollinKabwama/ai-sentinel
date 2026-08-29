package dev.aisentinel.core.enforcement;

import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MonitorOnlyEnforcementHandlerTest {

    @Test
    void isQuarantinedDelegatesIdentityAndEndpointToDelegate() {
        EnforcementHandler delegate = mock(EnforcementHandler.class);
        TelemetryEmitter telemetry = mock(TelemetryEmitter.class);
        when(delegate.isQuarantined("h1", "/api")).thenReturn(true);
        when(delegate.isQuarantined("h2", "/api")).thenReturn(false);

        MonitorOnlyEnforcementHandler handler = new MonitorOnlyEnforcementHandler(delegate, telemetry);

        assertThat(handler.isQuarantined("h1", "/api")).isTrue();
        assertThat(handler.isQuarantined("h2", "/api")).isFalse();
        verify(delegate).isQuarantined("h1", "/api");
        verify(delegate).isQuarantined("h2", "/api");
    }
}
