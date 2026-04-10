package io.aisentinel.core.telemetry;

/**
 * Emits structured telemetry for observability (logging, audit).
 * <p>
 * Called from the request path after scoring/enforcement. Implementations must treat emission as
 * <strong>best-effort</strong>: {@link #emit} must not throw or block indefinitely; failures must not change policy
 * or enforcement outcomes.
 */
public interface TelemetryEmitter {

    /**
     * Fire-and-forget event. Must not throw checked exceptions; unchecked failures should be swallowed or logged.
     */
    void emit(TelemetryEvent event);
}
