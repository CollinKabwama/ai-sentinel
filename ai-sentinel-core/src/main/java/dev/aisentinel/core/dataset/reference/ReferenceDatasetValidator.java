package dev.aisentinel.core.dataset.reference;

import dev.aisentinel.core.dataset.EvaluationDatasetSchemas;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Integrity validator for the tracked reference synthetic evaluation dataset.
 */
public final class ReferenceDatasetValidator {

    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("\"eventId\":\"([^\"]+)\"");
    private static final Pattern CHECKSUM_PATTERN = Pattern.compile("\"eventsSha256\":\"([0-9a-f]{64})\"");
    private static final Pattern RECORD_COUNT_PATTERN = Pattern.compile("\"recordCount\":([0-9]+)");
    private static final Pattern SCENARIO_PATTERN = Pattern.compile(
        "\\{\"id\":\"([^\"]+)\",\"category\":\"([^\"]+)\",\"expectedClass\":\"([^\"]+)\","
            + "\"anomalyExpected\":(true|false),\"maliciousnessAsserted\":(true|false),"
            + "\"eventIds\":\\[(.*?)]\\,\"baselineEventIds\":\\[(.*?)]\\,"
            + "\"evaluationEventIds\":\\[(.*?)]\\,\"identityKeys\":\\[(.*?)]\\,"
            + "\"exercisedFeatures\":\\[(.*?)]",
        Pattern.DOTALL);
    private static final Set<String> GENERATED_EVENT_LABELS = Set.of(
        "NORMAL",
        "SYNTHETIC_ANOMALOUS",
        "LEGITIMATE_ANOMALOUS",
        "ESTABLISHED_NORMAL_BASELINE",
        "WARMUP_NEW_IDENTITY",
        "RAPID_REQUEST_BURST",
        "ENDPOINT_BEHAVIOR_CHANGE",
        "PAYLOAD_SIZE_DEVIATION",
        "PARAMETER_COUNT_DEVIATION",
        "TOKEN_AGE_CHANGE",
        "LOW_VARIANCE_BASELINE_DEVIATION",
        "GRADUAL_BEHAVIOR_CHANGE",
        "LEGITIMATE_BULK_OPERATION",
        "INTERLEAVED_NORMAL_IDENTITIES"
    );

    public ValidationSummary validate(Path datasetDirectory) throws IOException {
        Path manifest = datasetDirectory.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME);
        Path events = datasetDirectory.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME);
        Path annotations = datasetDirectory.resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME);
        requireExists(manifest);
        requireExists(events);
        requireExists(annotations);

        byte[] manifestBytes = Files.readAllBytes(manifest);
        byte[] eventsBytes = Files.readAllBytes(events);
        byte[] annotationsBytes = Files.readAllBytes(annotations);
        String manifestText = new String(manifestBytes, StandardCharsets.UTF_8);
        String eventsText = new String(eventsBytes, StandardCharsets.UTF_8);
        String annotationsText = new String(annotationsBytes, StandardCharsets.UTF_8);

        requireNoSecrets(eventsText);
        requireNoSecrets(annotationsText);
        requireNoLabelsInEvents(eventsText);
        String manifestChecksum = extractChecksum(manifestText);
        String actualChecksum = TrainingFingerprintHashes.sha256HexBytes(eventsBytes);
        if (!manifestChecksum.equals(actualChecksum)) {
            throw new IllegalStateException("events checksum mismatch");
        }

        List<String> eventIds = extractEventIds(eventsText);
        if (eventIds.isEmpty()) {
            throw new IllegalStateException("reference dataset contains no events");
        }
        Set<String> unique = new HashSet<>(eventIds);
        if (unique.size() != eventIds.size()) {
            throw new IllegalStateException("duplicate event ids detected");
        }
        requireManifest(manifestText, eventIds.size(), actualChecksum);
        requireAnnotations(annotationsText, eventIds, eventsText);

        ReferenceDatasetGenerator.GeneratedReferenceDataset regenerated = regenerateToTemp();
        requireGeneratedIntegrity(regenerated);
        compareBytes("manifest", manifestBytes,
            Files.readAllBytes(regenerated.outputDirectory().resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME)));
        compareBytes("events", eventsBytes,
            Files.readAllBytes(regenerated.outputDirectory().resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME)));
        compareBytes("annotations", annotationsBytes,
            Files.readAllBytes(regenerated.outputDirectory().resolve(ReferenceDatasetGenerator.ANNOTATIONS_FILE_NAME)));

        return new ValidationSummary(
            eventIds.size(),
            regenerated.scenarioCount(),
            regenerated.identityCount(),
            Files.size(events),
            Files.size(annotations),
            Files.size(manifest),
            actualChecksum
        );
    }

    private static void requireExists(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("missing dataset artifact: " + path);
        }
    }

    private static String extractChecksum(String manifestText) {
        Matcher matcher = CHECKSUM_PATTERN.matcher(manifestText);
        if (!matcher.find()) {
            throw new IllegalStateException("manifest is missing eventsSha256");
        }
        return matcher.group(1);
    }

    private static void requireManifest(String manifestText, int eventCount, String actualChecksum) {
        requireJsonString(manifestText, "datasetSchemaVersion", EvaluationDatasetSchemas.DATASET_SCHEMA_VERSION);
        requireJsonString(manifestText, "datasetId", ReferenceDatasetGenerator.DATASET_ID);
        requireJsonString(manifestText, "createdAt", ReferenceDatasetGenerator.DATASET_CREATED_AT.toString());
        requireJsonString(manifestText, "aiSentinelVersion", ReferenceDatasetGenerator.AI_SENTINEL_VERSION);
        requireJsonString(manifestText, "featureSchemaVersion", FeatureSchema.VERSION_ID);
        requireJsonString(manifestText, "evaluationEventSchemaVersion",
            dev.aisentinel.core.contract.EvaluationEventSchemas.CURRENT_VERSION);
        requireJsonString(manifestText, "ordering", EvaluationDatasetSchemas.ORDERING_APPEND_ORDER);
        requireJsonString(manifestText, "sourceClassification", ReferenceDatasetGenerator.SOURCE_CLASSIFICATION);
        requireJsonString(manifestText, "transformationVersion", ReferenceDatasetGenerator.GENERATOR_VERSION);
        requireJsonString(manifestText, "eventsFile", EvaluationDatasetSchemas.EVENTS_FILE_NAME);
        requireJsonString(manifestText, "eventsSha256", actualChecksum);
        Matcher matcher = RECORD_COUNT_PATTERN.matcher(manifestText);
        if (!matcher.find()) {
            throw new IllegalStateException("manifest is missing recordCount");
        }
        if (Long.parseLong(matcher.group(1)) != eventCount) {
            throw new IllegalStateException("manifest recordCount mismatch");
        }
    }

    private static void requireJsonString(String json, String field, String expected) {
        String needle = "\"" + field + "\":\"" + expected + "\"";
        if (!json.contains(needle)) {
            throw new IllegalStateException("manifest " + field + " mismatch");
        }
    }

    private static void requireAnnotations(String annotationsText, List<String> eventIds, String eventsText) {
        if (!annotationsText.contains("\"schemaVersion\":\"" + ReferenceDatasetAnnotations.SCHEMA_VERSION + "\"")) {
            throw new IllegalStateException("annotation schemaVersion mismatch");
        }
        if (!annotationsText.contains("\"datasetId\":\"" + ReferenceDatasetGenerator.DATASET_ID + "\"")) {
            throw new IllegalStateException("annotation datasetId mismatch");
        }
        Map<String, Set<String>> eventIdentities = extractEventIdentities(eventsText);
        Set<String> eventIdSet = new LinkedHashSet<>(eventIds);
        Set<String> referencedOnce = new LinkedHashSet<>();
        Set<String> scenarioIds = new LinkedHashSet<>();
        Matcher matcher = SCENARIO_PATTERN.matcher(annotationsText);
        int scenarioCount = 0;
        while (matcher.find()) {
            scenarioCount++;
            String scenarioId = matcher.group(1);
            if (!scenarioIds.add(scenarioId)) {
                throw new IllegalStateException("duplicate scenario id: " + scenarioId);
            }
            requireEnum("category", matcher.group(2), ReferenceDatasetScenarioCategory.class);
            requireEnum("expectedClass", matcher.group(3), ReferenceDatasetExpectedClass.class);
            List<String> scenarioEvents = parseStringArray(matcher.group(6));
            List<String> baselineEvents = parseStringArray(matcher.group(7));
            List<String> evaluationEvents = parseStringArray(matcher.group(8));
            List<String> identities = parseStringArray(matcher.group(9));
            List<String> exercisedFeatures = parseStringArray(matcher.group(10));
            if (scenarioEvents.isEmpty()) {
                throw new IllegalStateException("scenario has no events: " + scenarioId);
            }
            if (identities.isEmpty()) {
                throw new IllegalStateException("scenario has no identities: " + scenarioId);
            }
            if (exercisedFeatures.isEmpty()) {
                throw new IllegalStateException("scenario has no exercised features: " + scenarioId);
            }
            requireAllKnown("scenario eventIds", scenarioEvents, eventIdSet);
            requireAllKnown("baselineEventIds", baselineEvents, new LinkedHashSet<>(scenarioEvents));
            requireAllKnown("evaluationEventIds", evaluationEvents, new LinkedHashSet<>(scenarioEvents));
            requireDisjoint("baselineEventIds", baselineEvents, "evaluationEventIds", evaluationEvents);
            for (String eventId : scenarioEvents) {
                if (!referencedOnce.add(eventId)) {
                    throw new IllegalStateException("event referenced by multiple scenarios: " + eventId);
                }
                Set<String> actualIdentities = eventIdentities.get(eventId);
                if (actualIdentities == null || actualIdentities.stream().noneMatch(identities::contains)) {
                    throw new IllegalStateException("scenario identity does not match event: " + eventId);
                }
            }
            requireIncreasingOrder("scenario eventIds", scenarioEvents, eventIds);
            requireIncreasingOrder("baselineEventIds", baselineEvents, eventIds);
            requireIncreasingOrder("evaluationEventIds", evaluationEvents, eventIds);
        }
        if (scenarioCount != ReferenceDatasetScenarioCategory.values().length) {
            throw new IllegalStateException("unexpected scenario count: " + scenarioCount);
        }
        if (!referencedOnce.equals(eventIdSet)) {
            throw new IllegalStateException("annotations do not reference exactly the event corpus");
        }
    }

    private static <E extends Enum<E>> void requireEnum(String field, String value, Class<E> type) {
        try {
            Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("unknown " + field + ": " + value, e);
        }
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

    private static Map<String, Set<String>> extractEventIdentities(String eventsText) {
        java.util.LinkedHashMap<String, Set<String>> identities = new java.util.LinkedHashMap<>();
        Pattern pattern = Pattern.compile("\"eventId\":\"([^\"]+)\".*?\"identityKey\":\"([^\"]+)\"", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(eventsText);
        while (matcher.find()) {
            identities.computeIfAbsent(matcher.group(1), ignored -> new LinkedHashSet<>()).add(matcher.group(2));
        }
        return Map.copyOf(identities);
    }

    private static void requireAllKnown(String field, List<String> values, Set<String> allowed) {
        for (String value : values) {
            if (!allowed.contains(value)) {
                throw new IllegalStateException(field + " contains unknown event id: " + value);
            }
        }
    }

    private static void requireDisjoint(String leftLabel, List<String> left, String rightLabel, List<String> right) {
        Set<String> rightSet = new LinkedHashSet<>(right);
        for (String value : left) {
            if (rightSet.contains(value)) {
                throw new IllegalStateException(leftLabel + " overlaps " + rightLabel + ": " + value);
            }
        }
    }

    private static void requireIncreasingOrder(String field, List<String> values, List<String> corpusOrder) {
        int previous = -1;
        for (String value : values) {
            int current = corpusOrder.indexOf(value);
            if (current <= previous) {
                throw new IllegalStateException(field + " is not in append order");
            }
            previous = current;
        }
    }

    private static void requireNoLabelsInEvents(String eventsText) {
        for (String label : GENERATED_EVENT_LABELS) {
            if (eventsText.contains(label)) {
                throw new IllegalStateException("label leaked into events artifact: " + label);
            }
        }
    }

    private static void requireGeneratedIntegrity(ReferenceDatasetGenerator.GeneratedReferenceDataset generated) {
        Set<String> eventIds = new LinkedHashSet<>();
        Set<String> identities = new LinkedHashSet<>();
        for (dev.aisentinel.core.contract.EvaluationEvent event : generated.events()) {
            eventIds.add(event.eventId());
            identities.add(event.identityKey());
        }
        if (eventIds.size() != generated.events().size()) {
            throw new IllegalStateException("generated duplicate event ids detected");
        }
        if (generated.annotations().scenarios().isEmpty()) {
            throw new IllegalStateException("generated annotations contain no scenarios");
        }
        Set<String> referenced = new LinkedHashSet<>();
        for (ReferenceDatasetScenarioAnnotation scenario : generated.annotations().scenarios()) {
            requireAllKnown("generated scenario eventIds", scenario.eventIds(), eventIds);
            requireAllKnown("generated baselineEventIds", scenario.baselineEventIds(), new LinkedHashSet<>(scenario.eventIds()));
            requireAllKnown("generated evaluationEventIds", scenario.evaluationEventIds(), new LinkedHashSet<>(scenario.eventIds()));
            requireDisjoint("generated baselineEventIds", scenario.baselineEventIds(),
                "generated evaluationEventIds", scenario.evaluationEventIds());
            for (String identity : scenario.identityKeys()) {
                if (!identities.contains(identity)) {
                    throw new IllegalStateException("generated scenario references unknown identity: " + identity);
                }
            }
            for (String eventId : scenario.eventIds()) {
                if (!referenced.add(eventId)) {
                    throw new IllegalStateException("generated event referenced by multiple scenarios: " + eventId);
                }
            }
        }
        if (!referenced.equals(eventIds)) {
            throw new IllegalStateException("generated annotations do not reference exactly the event corpus");
        }
    }

    private static List<String> extractEventIds(String eventsText) {
        Matcher matcher = EVENT_ID_PATTERN.matcher(eventsText);
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return List.copyOf(ids);
    }

    private static void compareBytes(String label, byte[] expected, byte[] actual) {
        if (!java.util.Arrays.equals(expected, actual)) {
            throw new IllegalStateException(label + " artifact differs from deterministic regeneration");
        }
    }

    private static void requireNoSecrets(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> forbidden = List.of(
            "bearer ",
            "\"authorization\"",
            "\"cookie\"",
            "\"set-cookie\"",
            "api_key",
            "x-api-key",
            "password",
            "@example.com",
            "?token=",
            "?apikey="
        );
        for (String marker : forbidden) {
            if (lower.contains(marker)) {
                throw new IllegalStateException("prohibited marker in dataset artifact: " + marker);
            }
        }
    }

    private static ReferenceDatasetGenerator.GeneratedReferenceDataset regenerateToTemp() throws IOException {
        Path tempDir = Files.createTempDirectory("reference-dataset-regen-");
        return new ReferenceDatasetGenerator().generate(tempDir);
    }

    public record ValidationSummary(
        int eventCount,
        int scenarioCount,
        int identityCount,
        long eventsBytes,
        long annotationsBytes,
        long manifestBytes,
        String eventsSha256
    ) {
        public long totalBytes() {
            return eventsBytes + annotationsBytes + manifestBytes;
        }
    }
}
