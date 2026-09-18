package dev.aisentinel.core.scoring.shadow;

/**
 * Framework-independent sink for structured shadow observations.
 * <p>
 * Implementations must treat emission as best-effort: failures must not change
 * authoritative scoring, policy, or enforcement outcomes.
 * <p>
 * {@code TELEMETRY FAILURE != REQUEST FAILURE}
 * <p>
 * Do not couple implementations in core to Kafka, Redis, HTTP exporters,
 * Micrometer registries, filesystems, or databases.
 */
public interface ShadowObservationSink {

    /** No-op default; safe for production wiring when no collector is configured. */
    ShadowObservationSink NOOP = observation -> {
        // intentionally empty
    };

    /**
     * Receives one observation. Must not throw checked exceptions; ordinary
     * unchecked failures are contained by {@link ShadowScoringExecutor}.
     */
    void accept(ShadowScoringObservation observation);
}
