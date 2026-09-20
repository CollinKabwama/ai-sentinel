package dev.aisentinel.core.dataset.corpus;

import dev.aisentinel.core.contract.EvaluationEventJson;
import dev.aisentinel.core.dataset.EvaluationDatasetManifest;
import dev.aisentinel.core.dataset.EvaluationDatasetSchemas;
import dev.aisentinel.core.dataset.EvaluationDatasetWriter;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Writes Kit corpus artifacts and a replay-compatible dataset manifest for the same events.
 */
final class CorpusArtifactWriter {

    private static final Instant FIXED_CREATED_AT = Instant.parse("2024-01-01T00:00:00Z");

    private CorpusArtifactWriter() {
    }

    static GeneratedCorpus write(
        Path outputDirectory,
        ScenarioDocument scenario,
        String scenarioJson,
        String seed,
        String generatorBuildId,
        CompiledCorpus compiled
    ) throws IOException {
        Files.createDirectories(outputDirectory);
        Path eventsPath = outputDirectory.resolve(CorpusGeneratorSchemas.EVENTS_FILE_NAME);
        Path corpusManifestPath = outputDirectory.resolve(CorpusGeneratorSchemas.CORPUS_MANIFEST_FILE_NAME);
        Path annotationsPath = outputDirectory.resolve(CorpusGeneratorSchemas.ANNOTATIONS_FILE_NAME);
        Path replayManifestPath = outputDirectory.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME);

        if (Files.exists(eventsPath)
            || Files.exists(corpusManifestPath)
            || Files.exists(annotationsPath)
            || Files.exists(replayManifestPath)) {
            throw new CorpusGeneratorException(
                "Output directory already contains corpus artifacts: " + outputDirectory);
        }

        String scenarioSha256 = TrainingFingerprintHashes.sha256HexUtf8(scenarioJson);
        String corpusId = corpusId(scenario.scenarioId(), scenario.scenarioVersion(), seed, generatorBuildId);
        String family = scenario.family() == null || scenario.family().isBlank()
            ? "unspecified"
            : scenario.family();

        try (EvaluationDatasetWriter writer = new EvaluationDatasetWriter(
            outputDirectory,
            corpusId,
            "0.4.0",
            scenario.featureSchemaVersion(),
            "1",
            CorpusGeneratorSchemas.SOURCE_CLASSIFICATION,
            CorpusGeneratorSchemas.TRANSFORMATION_VERSION,
            "Deterministic Evaluation Kit generated corpus (" + family + ")",
            scenario.scenarioId() + "@" + scenario.scenarioVersion(),
            FIXED_CREATED_AT
        )) {
            for (CompiledEvent compiledEvent : compiled.events()) {
                writer.append(compiledEvent.event());
            }
        }

        byte[] eventBytes = Files.readAllBytes(eventsPath);
        String eventsSha256 = TrainingFingerprintHashes.sha256HexBytes(eventBytes);
        rejectGroundTruthInEvents(eventBytes);

        String annotationsJson = writeAnnotationsJson(corpusId, scenario, compiled.events());
        Files.writeString(annotationsPath, annotationsJson, StandardCharsets.UTF_8);
        String annotationsSha256 = TrainingFingerprintHashes.sha256HexBytes(
            annotationsJson.getBytes(StandardCharsets.UTF_8));

        String corpusManifestJson = writeCorpusManifestJson(
            corpusId,
            scenario,
            seed,
            generatorBuildId,
            scenarioSha256,
            eventsSha256,
            annotationsSha256,
            compiled.events().size()
        );
        Files.writeString(corpusManifestPath, corpusManifestJson, StandardCharsets.UTF_8);

        return new GeneratedCorpus(
            corpusId,
            scenario.scenarioId(),
            scenario.scenarioVersion(),
            seed,
            CorpusGeneratorSchemas.GENERATOR_CONTRACT_VERSION,
            generatorBuildId,
            CorpusGeneratorSchemas.REPRESENTATION_MODE_FEATURE_LEVEL,
            outputDirectory,
            eventsPath,
            corpusManifestPath,
            annotationsPath,
            replayManifestPath,
            eventsSha256,
            annotationsSha256,
            scenarioSha256,
            compiled.events().size()
        );
    }

    static void verifyIntegrity(GeneratedCorpus corpus) throws IOException {
        String eventsSha = TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(corpus.eventsPath()));
        if (!eventsSha.equals(corpus.eventsSha256())) {
            throw new CorpusGeneratorException("Events checksum mismatch");
        }
        String annotationsSha = TrainingFingerprintHashes.sha256HexBytes(
            Files.readAllBytes(corpus.annotationsPath()));
        if (!annotationsSha.equals(corpus.annotationsSha256())) {
            throw new CorpusGeneratorException("Annotations checksum mismatch");
        }
        String manifestText = Files.readString(corpus.corpusManifestPath(), StandardCharsets.UTF_8);
        requireManifestChecksum(manifestText, "events", corpus.eventsSha256());
        requireManifestChecksum(manifestText, "annotations", corpus.annotationsSha256());

        EvaluationDatasetManifest replayManifest = readReplayManifestHint(corpus.replayManifestPath());
        if (!replayManifest.eventsSha256().equals(corpus.eventsSha256())) {
            throw new CorpusGeneratorException("Replay manifest events checksum mismatch");
        }
        if (replayManifest.recordCount() != corpus.eventCount()) {
            throw new CorpusGeneratorException("Replay manifest recordCount mismatch");
        }
    }

    private static EvaluationDatasetManifest readReplayManifestHint(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        String eventsSha256 = requireJsonString(json, "eventsSha256");
        long recordCount = Long.parseLong(requireJsonNumber(json, "recordCount"));
        return new EvaluationDatasetManifest(
            requireJsonString(json, "datasetSchemaVersion"),
            requireJsonString(json, "datasetId"),
            Instant.parse(requireJsonString(json, "createdAt")),
            requireJsonString(json, "aiSentinelVersion"),
            requireJsonString(json, "featureSchemaVersion"),
            requireJsonString(json, "evaluationEventSchemaVersion"),
            recordCount,
            requireJsonString(json, "ordering"),
            requireJsonString(json, "sourceClassification"),
            requireJsonString(json, "transformationVersion"),
            requireJsonString(json, "eventsFile"),
            eventsSha256,
            optionalJsonString(json, "description"),
            optionalJsonString(json, "scenario")
        );
    }

    private static void requireManifestChecksum(String manifestJson, String role, String expectedSha) {
        if (!manifestJson.contains("\"role\":\"" + role + "\"")) {
            throw new CorpusGeneratorException("Corpus manifest missing artifact role: " + role);
        }
        if (!manifestJson.contains("\"sha256\":\"" + expectedSha + "\"")) {
            throw new CorpusGeneratorException("Corpus manifest checksum mismatch for role: " + role);
        }
    }

    private static void rejectGroundTruthInEvents(byte[] eventBytes) {
        String text = new String(eventBytes, StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            EvaluationEventJson.parse(line);
        }
    }

    private static String writeAnnotationsJson(
        String corpusId,
        ScenarioDocument scenario,
        List<CompiledEvent> events
    ) {
        StringBuilder json = new StringBuilder(256 + events.size() * 96);
        json.append('{');
        appendString(json, "annotationSchemaVersion", CorpusGeneratorSchemas.ANNOTATION_SCHEMA_VERSION, true);
        appendString(json, "annotationId", "ann." + corpusId, false);
        appendString(json, "corpusId", corpusId, false);
        appendString(json, "scenarioId", scenario.scenarioId(), false);
        json.append(",\"annotations\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            CompiledEvent event = events.get(i);
            json.append('{');
            appendString(json, "eventId", event.event().eventId(), true);
            appendString(json, "scenarioId", scenario.scenarioId(), false);
            appendString(json, "expectedClass", event.expectedClass(), false);
            appendString(json, "category", event.category(), false);
            json.append('}');
        }
        json.append(']');
        appendString(json, "notes",
            "Sidecar only. Join by eventId at evaluation time. Never embed in EvaluationEvent.",
            false);
        json.append('}');
        json.append('\n');
        return json.toString();
    }

    private static String writeCorpusManifestJson(
        String corpusId,
        ScenarioDocument scenario,
        String seed,
        String generatorBuildId,
        String scenarioSha256,
        String eventsSha256,
        String annotationsSha256,
        int eventCount
    ) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendString(json, "corpusSchemaVersion", CorpusGeneratorSchemas.CORPUS_SCHEMA_VERSION, true);
        appendString(json, "corpusId", corpusId, false);
        appendString(json, "scenarioId", scenario.scenarioId(), false);
        appendString(json, "scenarioVersion", scenario.scenarioVersion(), false);
        appendString(json, "scenarioSha256", scenarioSha256, false);
        appendString(json, "seed", seed, false);
        appendString(json, "generatorContractVersion", CorpusGeneratorSchemas.GENERATOR_CONTRACT_VERSION, false);
        appendString(json, "generatorBuildId", generatorBuildId, false);
        appendString(json, "featureSchemaVersion", scenario.featureSchemaVersion(), false);
        appendString(json, "evaluationEventSchemaVersion", "1", false);
        appendString(json, "representationMode", CorpusGeneratorSchemas.REPRESENTATION_MODE_FEATURE_LEVEL, false);
        json.append(",\"artifacts\":[{");
        appendString(json, "role", "events", true);
        appendString(json, "path", CorpusGeneratorSchemas.EVENTS_FILE_NAME, false);
        appendString(json, "sha256", eventsSha256, false);
        appendString(json, "mediaType", "application/x-ndjson", false);
        json.append("}],\"annotationsArtifact\":{");
        appendString(json, "role", "annotations", true);
        appendString(json, "path", CorpusGeneratorSchemas.ANNOTATIONS_FILE_NAME, false);
        appendString(json, "sha256", annotationsSha256, false);
        appendString(json, "mediaType", "application/json", false);
        json.append("},\"timing\":{");
        appendNumber(json, "warmupDurationSeconds", scenario.warmupDurationSeconds(), true);
        appendNumber(json, "evaluationDurationSeconds", scenario.evaluationDurationSeconds(), false);
        json.append('}');
        appendNumber(json, "eventCount", eventCount, false);
        appendString(json, "notes",
            "Deterministic feature-level corpus generated from Evaluation Kit scenario.",
            false);
        json.append('}');
        json.append('\n');
        return json.toString();
    }

    private static String corpusId(
        String scenarioId,
        String scenarioVersion,
        String seed,
        String generatorBuildId
    ) {
        // Bind identity to every declared determinism axis (see EVALUATION_KIT.md §3), including
        // generatorContractVersion: even though this MVP only ever produces "1", a future second
        // contract version reusing the same scenario/seed/build combination must not collide.
        String token = TrainingFingerprintHashes.sha256HexUtf8(
            scenarioId + "|" + scenarioVersion + "|" + seed + "|"
                + CorpusGeneratorSchemas.GENERATOR_CONTRACT_VERSION + "|" + generatorBuildId).substring(0, 12);
        return "corpus." + sanitize(scenarioId) + "." + token;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendNumber(StringBuilder json, String field, long value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":").append(value);
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String requireJsonString(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(field) + "\":\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) {
            throw new CorpusGeneratorException("Missing field in replay manifest: " + field);
        }
        return matcher.group(1);
    }

    private static String optionalJsonString(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(field) + "\":\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String requireJsonNumber(String json, String field) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(field) + "\":(-?[0-9]+)").matcher(json);
        if (!matcher.find()) {
            throw new CorpusGeneratorException("Missing numeric field in replay manifest: " + field);
        }
        return matcher.group(1);
    }
}
