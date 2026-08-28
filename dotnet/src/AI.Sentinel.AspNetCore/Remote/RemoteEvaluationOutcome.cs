namespace AI.Sentinel.AspNetCore.Remote;

/// <summary>Closed outcome codes for remote evaluation telemetry (mirrors Java RemoteEvaluationOutcome).</summary>
public enum RemoteEvaluationOutcome
{
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
