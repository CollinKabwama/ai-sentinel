package dev.aisentinel.core.evaluation;

import java.time.Duration;
import java.util.Objects;

/**
 * One contiguous anomalous truth segment and its first-detection outcome.
 */
public record TemporalAnomalySegment(
    int segmentIndex,
    TemporalObservationPoint anomalyOnset,
    TemporalObservationPoint anomalyWindowEnd,
    int anomalyObservationCount,
    boolean detected,
    TemporalObservationPoint firstDetection,
    Integer detectionObservationDelay,
    Integer evaluableObservationDelay,
    Duration detectionTimeDelay,
    long unavailableObservationCount,
    TemporalRecoveryEvaluation recovery
) {
    public TemporalAnomalySegment {
        if (segmentIndex < 0) {
            throw new IllegalArgumentException("segmentIndex must be >= 0");
        }
        anomalyOnset = Objects.requireNonNull(anomalyOnset, "anomalyOnset");
        anomalyWindowEnd = Objects.requireNonNull(anomalyWindowEnd, "anomalyWindowEnd");
        if (anomalyWindowEnd.sequenceNumber() < anomalyOnset.sequenceNumber()) {
            throw new IllegalArgumentException("anomalyWindowEnd must not precede anomalyOnset");
        }
        if (anomalyWindowEnd.observedAt().isBefore(anomalyOnset.observedAt())) {
            throw new IllegalArgumentException("anomalyWindowEnd observedAt must not precede anomalyOnset");
        }
        if (anomalyObservationCount <= 0) {
            throw new IllegalArgumentException("anomalyObservationCount must be >= 1");
        }
        if (unavailableObservationCount < 0L || unavailableObservationCount > anomalyObservationCount) {
            throw new IllegalArgumentException("unavailableObservationCount must be within [0,anomalyObservationCount]");
        }
        if (!detected) {
            if (firstDetection != null
                || detectionObservationDelay != null
                || evaluableObservationDelay != null
                || detectionTimeDelay != null) {
                throw new IllegalArgumentException("undetected segment must not contain detection point or delays");
            }
        } else {
            firstDetection = Objects.requireNonNull(firstDetection, "firstDetection");
            detectionObservationDelay = Objects.requireNonNull(detectionObservationDelay, "detectionObservationDelay");
            evaluableObservationDelay = Objects.requireNonNull(evaluableObservationDelay, "evaluableObservationDelay");
            detectionTimeDelay = Objects.requireNonNull(detectionTimeDelay, "detectionTimeDelay");
            if (detectionObservationDelay < 0 || evaluableObservationDelay < 0) {
                throw new IllegalArgumentException("detection delays must be >= 0");
            }
            if (detectionObservationDelay >= anomalyObservationCount) {
                throw new IllegalArgumentException("detectionObservationDelay must be within anomaly window");
            }
            if (evaluableObservationDelay > detectionObservationDelay) {
                throw new IllegalArgumentException("evaluableObservationDelay must not exceed detectionObservationDelay");
            }
            if (firstDetection.sequenceNumber() < anomalyOnset.sequenceNumber()
                || firstDetection.sequenceNumber() > anomalyWindowEnd.sequenceNumber()) {
                throw new IllegalArgumentException("firstDetection must be within anomaly window");
            }
            if (firstDetection.observedAt().isBefore(anomalyOnset.observedAt())
                || firstDetection.observedAt().isAfter(anomalyWindowEnd.observedAt())) {
                throw new IllegalArgumentException("firstDetection observedAt must be within anomaly window");
            }
            if (detectionTimeDelay.isNegative()) {
                throw new IllegalArgumentException("detectionTimeDelay must be >= 0");
            }
            if (!detectionTimeDelay.equals(Duration.between(anomalyOnset.observedAt(), firstDetection.observedAt()))) {
                throw new IllegalArgumentException("detectionTimeDelay must match anomalyOnset to firstDetection");
            }
        }
    }
}
