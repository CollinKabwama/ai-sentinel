package dev.aisentinel.core.scoring.lifecycle;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Objective metric delta: {@code challenger - champion}.
 * <p>
 * {@code DELTA != GOVERNANCE DECISION}
 */
public final class MetricDelta {

    private final String metricName;
    private final Double championValue;
    private final Double challengerValue;
    private final Double delta;

    private MetricDelta(String metricName, Double championValue, Double challengerValue, Double delta) {
        this.metricName = Objects.requireNonNull(metricName, "metricName");
        if (metricName.isBlank()) {
            throw new IllegalArgumentException("metricName must be non-blank");
        }
        requireFiniteIfPresent(championValue, "championValue");
        requireFiniteIfPresent(challengerValue, "challengerValue");
        requireFiniteIfPresent(delta, "delta");
        this.championValue = championValue;
        this.challengerValue = challengerValue;
        this.delta = delta;
    }

    public static MetricDelta ofDefined(String metricName, double champion, double challenger) {
        return new MetricDelta(metricName, champion, challenger, challenger - champion);
    }

    public static MetricDelta undefined(String metricName, Double championOrNull, Double challengerOrNull) {
        return new MetricDelta(metricName, championOrNull, challengerOrNull, null);
    }

    public String metricName() {
        return metricName;
    }

    public OptionalDouble championValue() {
        return championValue == null ? OptionalDouble.empty() : OptionalDouble.of(championValue);
    }

    public OptionalDouble challengerValue() {
        return challengerValue == null ? OptionalDouble.empty() : OptionalDouble.of(challengerValue);
    }

    public OptionalDouble delta() {
        return delta == null ? OptionalDouble.empty() : OptionalDouble.of(delta);
    }

    public boolean defined() {
        return delta != null;
    }

    private static void requireFiniteIfPresent(Double value, String name) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite when present");
        }
    }
}
