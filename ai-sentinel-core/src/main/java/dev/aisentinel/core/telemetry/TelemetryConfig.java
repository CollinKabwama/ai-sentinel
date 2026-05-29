package dev.aisentinel.core.telemetry;

/**
 * Configuration for telemetry log verbosity.
 */
public record TelemetryConfig(
    LogVerbosity logVerbosity,
    double logScoreThreshold,
    int logSampleRate
) {
    public enum LogVerbosity {
        /** Log every event */
        FULL,
        /** Log only when score >= threshold or action is not ALLOW */
        ANOMALY_ONLY,
        /** Log every Nth event */
        SAMPLED,
        /** No JSON logs (metrics only) */
        NONE
    }

    public static TelemetryConfig defaults() {
        return new TelemetryConfig(LogVerbosity.FULL, 0.4, 100);
    }
}
