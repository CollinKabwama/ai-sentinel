package dev.aisentinel.core.enforcement;

import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import dev.aisentinel.core.http.HttpRequestView;
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
    public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
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
