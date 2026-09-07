package dev.aisentinel.core.dataset.reference;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned ground-truth annotation index for the reference synthetic evaluation dataset.
 */
public record ReferenceDatasetAnnotations(
    String schemaVersion,
    String datasetId,
    String description,
    List<ReferenceDatasetScenarioAnnotation> scenarios
) {
    public static final String SCHEMA_VERSION = "1";

    public ReferenceDatasetAnnotations {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported annotation schema version: " + schemaVersion);
        }
        datasetId = requireNotBlank("datasetId", datasetId);
        description = description == null ? "" : description;
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        Set<String> ids = new LinkedHashSet<>();
        for (ReferenceDatasetScenarioAnnotation scenario : scenarios) {
            Objects.requireNonNull(scenario, "scenario");
            if (!ids.add(scenario.id())) {
                throw new IllegalArgumentException("duplicate scenario id: " + scenario.id());
            }
        }
    }

    private static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}
