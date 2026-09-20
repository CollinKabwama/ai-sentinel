package dev.aisentinel.core.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Ordered feature subset projection for one consumer.
 */
public record FeatureProjection(
    FeatureProjectionId id,
    List<String> orderedFeatureNames
) {
    public FeatureProjection {
        Objects.requireNonNull(id, "id");
        orderedFeatureNames = orderedFeatureNames == null ? List.of() : List.copyOf(orderedFeatureNames);
        if (orderedFeatureNames.isEmpty()) {
            throw new IllegalArgumentException("orderedFeatureNames must not be empty");
        }
        HashSet<String> seen = new HashSet<>();
        for (String featureName : orderedFeatureNames) {
            if (featureName == null || featureName.isBlank()) {
                throw new IllegalArgumentException("orderedFeatureNames must not contain blank names");
            }
            if (!seen.add(featureName)) {
                throw new IllegalArgumentException("orderedFeatureNames must not contain duplicates: " + featureName);
            }
        }
    }
}
