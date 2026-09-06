package dev.aisentinel.core.model;

import java.util.Objects;

/**
 * One canonical feature definition within a versioned {@link FeatureSchema}.
 */
public record FeatureDefinition(
    String name,
    FeatureValueType valueType,
    String unit,
    String description
) {
    public FeatureDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(valueType, "valueType");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        unit = unit == null ? "" : unit;
    }
}
