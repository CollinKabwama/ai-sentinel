package dev.aisentinel.core.metrics;

/**
 * Closed outcome codes for remote evaluation client metrics/telemetry.
 * Do not use free-form exception messages as metric labels.
 */
public enum RemoteEvaluationOutcome {
    SUCCESS,
    TIMEOUT,
    CONNECTION_FAILURE,
    AUTH_REJECTED,
    HTTP_ERROR,
    VERSION_MISMATCH,
    MALFORMED_RESPONSE,
    CORRELATION_MISMATCH,
    SERIALIZATION_FAILURE,
    UNEXPECTED
}
