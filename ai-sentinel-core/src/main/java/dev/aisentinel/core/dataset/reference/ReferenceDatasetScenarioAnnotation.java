package dev.aisentinel.core.dataset.reference;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ground-truth annotation for one behavioral sequence in the reference dataset.
 */
public record ReferenceDatasetScenarioAnnotation(
    String id,
    ReferenceDatasetScenarioCategory category,
    ReferenceDatasetExpectedClass expectedClass,
    boolean anomalyExpected,
    boolean maliciousnessAsserted,
    List<String> eventIds,
    List<String> baselineEventIds,
    List<String> evaluationEventIds,
    List<String> identityKeys,
    List<String> exercisedFeatures,
    String notes
) {
    public ReferenceDatasetScenarioAnnotation {
        id = requireNotBlank("id", id);
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (expectedClass == null) {
            throw new IllegalArgumentException("expectedClass is required");
        }
        eventIds = copyDistinctRequired("eventIds", eventIds);
        baselineEventIds = copyDistinct("baselineEventIds", baselineEventIds);
        evaluationEventIds = copyDistinct("evaluationEventIds", evaluationEventIds);
        identityKeys = copyDistinctRequired("identityKeys", identityKeys);
        exercisedFeatures = copyDistinctRequired("exercisedFeatures", exercisedFeatures);
        notes = notes == null ? "" : notes;
        requireSubset("baselineEventIds", baselineEventIds, eventIds);
        requireSubset("evaluationEventIds", evaluationEventIds, eventIds);
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static List<String> copyDistinctRequired(String field, List<String> values) {
        List<String> copy = copyDistinct(field, values);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return copy;
    }

    private static List<String> copyDistinct(String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " contains blank value");
            }
            seen.add(value);
        }
        return List.copyOf(seen);
    }

    private static void requireSubset(String field, List<String> subset, List<String> superset) {
        Set<String> allowed = new LinkedHashSet<>(superset);
        for (String value : subset) {
            if (!allowed.contains(value)) {
                throw new IllegalArgumentException(field + " contains event id not present in eventIds: " + value);
            }
        }
    }
}
