package dev.aisentinel.core.enforcement;

import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import dev.aisentinel.core.http.HttpRequestView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Wrapper that logs enforcement actions but never blocks and never mutates enforcement state.
 * <p>
 * Denying actions ({@code THROTTLE}/{@code BLOCK}/{@code QUARANTINE}) emit {@code MONITOR_WOULD_*}
 * telemetry only — the delegate {@link #apply} path is not invoked, so local quarantine maps and
 * cluster quarantine publishes are not written. {@link #isQuarantined} and {@link #releaseQuarantine}
 * still delegate so existing ENFORCE-era state remains readable and operator-recoverable.
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

    /**
     * Operator recovery still works in MONITOR: release is forwarded to the delegate so local/cluster
     * state can be cleared without applying new enforcement writes.
     */
    @Override
    public boolean releaseQuarantine(String identityHash, String endpoint) {
        return delegate.releaseQuarantine(identityHash, endpoint);
    }
}
