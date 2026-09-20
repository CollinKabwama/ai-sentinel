package dev.aisentinel.core.dataset.reference;

import dev.aisentinel.core.replay.ReplayException;
import dev.aisentinel.core.replay.ReplayFailureKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal typed loader for the tracked reference annotation contract.
 */
public final class ReferenceDatasetAnnotationsLoader {

    private static final Pattern SCENARIO_PATTERN = Pattern.compile(
        "\\{\"id\":\"([^\"]+)\",\"category\":\"([^\"]+)\",\"expectedClass\":\"([^\"]+)\","
            + "\"anomalyExpected\":(true|false),\"maliciousnessAsserted\":(true|false),"
            + "\"eventIds\":\\[(.*?)]\\,\"baselineEventIds\":\\[(.*?)]\\,"
            + "\"evaluationEventIds\":\\[(.*?)]\\,\"identityKeys\":\\[(.*?)]\\,"
            + "\"exercisedFeatures\":\\[(.*?)]\\,\"notes\":\"([^\"]*)\"\\}",
        Pattern.DOTALL);

    public ReferenceDatasetAnnotations load(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    public ReferenceDatasetAnnotations parse(String json) {
        try {
            String schemaVersion = requireString(json, "schemaVersion");
            String datasetId = requireString(json, "datasetId");
            String description = optionalString(json, "description");
            Matcher matcher = SCENARIO_PATTERN.matcher(json);
            List<ReferenceDatasetScenarioAnnotation> scenarios = new ArrayList<>();
            while (matcher.find()) {
                scenarios.add(new ReferenceDatasetScenarioAnnotation(
                    matcher.group(1),
                    ReferenceDatasetScenarioCategory.valueOf(matcher.group(2)),
                    ReferenceDatasetExpectedClass.valueOf(matcher.group(3)),
                    Boolean.parseBoolean(matcher.group(4)),
                    Boolean.parseBoolean(matcher.group(5)),
                    parseStringArray(matcher.group(6)),
                    parseStringArray(matcher.group(7)),
                    parseStringArray(matcher.group(8)),
                    parseStringArray(matcher.group(9)),
                    parseStringArray(matcher.group(10)),
                    matcher.group(11)
                ));
            }
            return new ReferenceDatasetAnnotations(schemaVersion, datasetId, description, scenarios);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Failed to parse reference dataset annotations", e);
        }
    }

    private static String requireString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Missing annotation field: " + field);
        }
        return matcher.group(1);
    }

    private static String optionalString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\":\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> parseStringArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(raw);
        List<String> values = new ArrayList<>();
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return List.copyOf(values);
    }
}
