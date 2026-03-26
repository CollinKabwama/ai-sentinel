package io.aisentinel.core.enforcement;

import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import io.aisentinel.core.telemetry.TelemetryEvent;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Wrapper that logs enforcement actions but never blocks.
 */
@Slf4j
@RequiredArgsConstructor
public final class MonitorOnlyEnforcementHandler implements EnforcementHandler {

    private final EnforcementHandler delegate;
    private final TelemetryEmitter telemetry;

    @Override
    public boolean apply(EnforcementAction action, HttpServletRequest request, HttpServletResponse response,
                         String identityHash, String endpoint) {
        if (action == EnforcementAction.BLOCK || action == EnforcementAction.QUARANTINE || action == EnforcementAction.THROTTLE) {
            log.debug("Monitor mode: would apply {} for endpoint={}", action, endpoint);
            telemetry.emit(TelemetryEvent.policyActionApplied(identityHash, endpoint, "MONITOR_WOULD_" + action, null));
        }
        return true;
    }

    @Override
    public boolean isQuarantined(String identityHash, String endpoint) {
        return delegate.isQuarantined(identityHash, endpoint);
    }
}
