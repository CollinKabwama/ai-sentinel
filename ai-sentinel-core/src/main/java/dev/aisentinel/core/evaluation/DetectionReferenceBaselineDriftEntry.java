package dev.aisentinel.core.evaluation;

import java.util.Objects;

/**
 * One deterministic difference between official baseline and current evaluation.
 * <p>
 * Values are diagnostic summaries (IDs, hashes, counts, metrics). They must not contain
 * raw request/identity/feature content.
 */
public record DetectionReferenceBaselineDriftEntry(
    DetectionReferenceBaselineDriftCategory category,
    String field,
    String baselineValue,
    String currentValue
) {
    public DetectionReferenceBaselineDriftEntry {
        category = Objects.requireNonNull(category, "category");
        field = requireNotBlank("field", field);
        baselineValue = baselineValue == null ? "" : baselineValue;
        currentValue = currentValue == null ? "" : currentValue;
    }

    private static String requireNotBlank(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
