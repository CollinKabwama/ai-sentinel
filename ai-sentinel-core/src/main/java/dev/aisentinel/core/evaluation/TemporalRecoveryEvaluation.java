package dev.aisentinel.core.evaluation;

import java.time.Duration;
import java.util.Objects;

/**
 * Recovery/stabilization result for the normal segment following an anomalous segment.
 */
public record TemporalRecoveryEvaluation(
    TemporalObservationPoint recoveryOnset,
    TemporalObservationPoint recoveryWindowEnd,
    int recoveryObservationCount,
    boolean stabilized,
    TemporalObservationPoint firstStableNormalPrediction,
    Integer recoveryObservationDelay,
    Integer evaluableRecoveryObservationDelay,
    Duration recoveryTimeDelay,
    long unavailableObservationCount
) {
    public TemporalRecoveryEvaluation {
        recoveryOnset = Objects.requireNonNull(recoveryOnset, "recoveryOnset");
        recoveryWindowEnd = Objects.requireNonNull(recoveryWindowEnd, "recoveryWindowEnd");
        if (recoveryWindowEnd.sequenceNumber() < recoveryOnset.sequenceNumber()) {
            throw new IllegalArgumentException("recoveryWindowEnd must not precede recoveryOnset");
        }
        if (recoveryWindowEnd.observedAt().isBefore(recoveryOnset.observedAt())) {
            throw new IllegalArgumentException("recoveryWindowEnd observedAt must not precede recoveryOnset");
        }
        if (recoveryObservationCount <= 0) {
            throw new IllegalArgumentException("recoveryObservationCount must be >= 1");
        }
        if (unavailableObservationCount < 0L || unavailableObservationCount > recoveryObservationCount) {
            throw new IllegalArgumentException("unavailableObservationCount must be within [0,recoveryObservationCount]");
        }
        if (!stabilized) {
            if (firstStableNormalPrediction != null
                || recoveryObservationDelay != null
                || evaluableRecoveryObservationDelay != null
                || recoveryTimeDelay != null) {
                throw new IllegalArgumentException("unstabilized recovery must not contain recovery point or delays");
            }
        } else {
            firstStableNormalPrediction = Objects.requireNonNull(firstStableNormalPrediction, "firstStableNormalPrediction");
            recoveryObservationDelay = Objects.requireNonNull(recoveryObservationDelay, "recoveryObservationDelay");
            evaluableRecoveryObservationDelay = Objects.requireNonNull(
                evaluableRecoveryObservationDelay,
                "evaluableRecoveryObservationDelay"
            );
            recoveryTimeDelay = Objects.requireNonNull(recoveryTimeDelay, "recoveryTimeDelay");
            if (recoveryObservationDelay < 0 || evaluableRecoveryObservationDelay < 0) {
                throw new IllegalArgumentException("recovery delays must be >= 0");
            }
            if (recoveryObservationDelay >= recoveryObservationCount) {
                throw new IllegalArgumentException("recoveryObservationDelay must be within recovery window");
            }
            if (evaluableRecoveryObservationDelay > recoveryObservationDelay) {
                throw new IllegalArgumentException("evaluableRecoveryObservationDelay must not exceed recoveryObservationDelay");
            }
            if (firstStableNormalPrediction.sequenceNumber() < recoveryOnset.sequenceNumber()
                || firstStableNormalPrediction.sequenceNumber() > recoveryWindowEnd.sequenceNumber()) {
                throw new IllegalArgumentException("firstStableNormalPrediction must be within recovery window");
            }
            if (firstStableNormalPrediction.observedAt().isBefore(recoveryOnset.observedAt())
                || firstStableNormalPrediction.observedAt().isAfter(recoveryWindowEnd.observedAt())) {
                throw new IllegalArgumentException("firstStableNormalPrediction observedAt must be within recovery window");
            }
            if (recoveryTimeDelay.isNegative()) {
                throw new IllegalArgumentException("recoveryTimeDelay must be >= 0");
            }
            if (!recoveryTimeDelay.equals(Duration.between(recoveryOnset.observedAt(), firstStableNormalPrediction.observedAt()))) {
                throw new IllegalArgumentException("recoveryTimeDelay must match recoveryOnset to firstStableNormalPrediction");
            }
        }
    }
}
