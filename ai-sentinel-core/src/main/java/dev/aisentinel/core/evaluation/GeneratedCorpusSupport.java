package dev.aisentinel.core.evaluation;

import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads and integrity-checks generated Evaluation Kit corpus directories.
 * <p>
 * Uses Kit {@code corpus-manifest.json} for provenance/checksums and leaves replay loading to
 * {@link dev.aisentinel.core.replay.ReplayDatasetLoader} via the dual-written replay {@code manifest.json}.
 */
final class GeneratedCorpusSupport {

    static final String CORPUS_MANIFEST_FILE = "corpus-manifest.json";
    static final String ANNOTATIONS_FILE = "annotations.json";
    static final String EVENTS_FILE = "events.jsonl";
    static final String REPLAY_MANIFEST_FILE = "manifest.json";

    private GeneratedCorpusSupport() {
    }

    static LoadedCorpus load(Path corpusDirectory) throws IOException {
        Path dir = requireDirectory(corpusDirectory);
        Path corpusManifestPath = requireFile(dir.resolve(CORPUS_MANIFEST_FILE));
        Path annotationsPath = requireFile(dir.resolve(ANNOTATIONS_FILE));
        Path eventsPath = requireFile(dir.resolve(EVENTS_FILE));
        requireFile(dir.resolve(REPLAY_MANIFEST_FILE));

        String corpusManifestJson = Files.readString(corpusManifestPath, StandardCharsets.UTF_8);
        GeneratedCorpusProvenance provenance = parseProvenance(corpusManifestJson);
        verifyChecksum(eventsPath, provenance.eventsSha256(), "events");
        verifyChecksum(annotationsPath, provenance.annotationsSha256(), "annotations");

        long eventLines = Files.lines(eventsPath, StandardCharsets.UTF_8).filter(line -> !line.isBlank()).count();
        if (eventLines != provenance.eventCount()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Event count mismatch: manifest=" + provenance.eventCount() + " file=" + eventLines);
        }

        GeneratedCorpusGroundTruth groundTruth = parseGroundTruth(
            Files.readString(annotationsPath, StandardCharsets.UTF_8));
        if (!groundTruth.corpusId().equals(provenance.corpusId())) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "annotations corpusId does not match corpus-manifest corpusId");
        }
        if (!groundTruth.scenarioId().equals(provenance.scenarioId())) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "annotations scenarioId does not match corpus-manifest scenarioId");
        }
        if (!"1".equals(groundTruth.annotationSchemaVersion())) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Unsupported annotationSchemaVersion: " + groundTruth.annotationSchemaVersion());
        }

        return new LoadedCorpus(dir, provenance, groundTruth, eventsPath, annotationsPath);
    }

    static void validateGroundTruthAgainstEvents(
        GeneratedCorpusGroundTruth groundTruth,
        Set<String> eventIds
    ) {
        Set<String> seen = new LinkedHashSet<>();
        for (GeneratedCorpusGroundTruth.EventAnnotation annotation : groundTruth.annotations()) {
            if (!eventIds.contains(annotation.eventId())) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                    "Annotation references nonexistent eventId: " + annotation.eventId());
            }
            if (!seen.add(annotation.eventId())) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                    "Duplicate annotation eventId: " + annotation.eventId());
            }
            if (!annotation.scenarioId().equals(groundTruth.scenarioId())) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                    "Annotation scenarioId mismatch for eventId: " + annotation.eventId());
            }
        }
        for (String eventId : eventIds) {
            if (!seen.contains(eventId)) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                    "Event missing ground-truth annotation: " + eventId);
            }
        }
    }

    static GeneratedCorpusPhaseCounts phaseCounts(GeneratedCorpusGroundTruth groundTruth) {
        int warmup = 0;
        int unknown = 0;
        int benign = 0;
        int anomalous = 0;
        for (GeneratedCorpusGroundTruth.EventAnnotation annotation : groundTruth.annotations()) {
            if (annotation.warmup()) {
                warmup++;
                continue;
            }
            if (annotation.unknownOrUnlabeled()) {
                unknown++;
                continue;
            }
            if ("benign".equals(annotation.expectedClass())) {
                benign++;
            } else if ("anomalous".equals(annotation.expectedClass())) {
                anomalous++;
            }
        }
        return new GeneratedCorpusPhaseCounts(
            groundTruth.annotations().size(),
            warmup,
            unknown,
            benign + anomalous,
            benign,
            anomalous
        );
    }

    private static GeneratedCorpusProvenance parseProvenance(String json) {
        String schema = requireString(json, "corpusSchemaVersion");
        if (!"1".equals(schema)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Unsupported corpusSchemaVersion: " + schema);
        }
        String eventsSha = artifactSha(json, "events");
        String annotationsSha = nestedArtifactSha(json, "annotationsArtifact");
        String timing = requireObject(json, "timing");
        return new GeneratedCorpusProvenance(
            requireString(json, "corpusId"),
            requireString(json, "scenarioId"),
            requireString(json, "scenarioVersion"),
            requireString(json, "scenarioSha256"),
            requireString(json, "seed"),
            requireString(json, "generatorContractVersion"),
            requireString(json, "generatorBuildId"),
            requireString(json, "featureSchemaVersion"),
            requireString(json, "evaluationEventSchemaVersion"),
            requireString(json, "representationMode"),
            eventsSha,
            annotationsSha,
            requireInt(json, "eventCount"),
            requireInt(timing, "warmupDurationSeconds"),
            requireInt(timing, "evaluationDurationSeconds")
        );
    }

    private static GeneratedCorpusGroundTruth parseGroundTruth(String json) {
        String annotationsBody = requireArrayBody(json, "annotations");
        List<String> objects = splitTopLevelObjects(annotationsBody);
        if (objects.isEmpty()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE,
                "annotations array must not be empty");
        }
        List<GeneratedCorpusGroundTruth.EventAnnotation> annotations = new ArrayList<>(objects.size());
        for (String object : objects) {
            annotations.add(new GeneratedCorpusGroundTruth.EventAnnotation(
                requireString(object, "eventId"),
                requireString(object, "scenarioId"),
                requireString(object, "expectedClass"),
                requireString(object, "category")
            ));
        }
        return new GeneratedCorpusGroundTruth(
            requireString(json, "annotationSchemaVersion"),
            requireString(json, "annotationId"),
            requireString(json, "corpusId"),
            requireString(json, "scenarioId"),
            annotations,
            optionalString(json, "notes")
        );
    }

    private static void verifyChecksum(Path path, String expectedSha, String role) throws IOException {
        String actual = TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(path));
        if (!actual.equals(expectedSha)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Checksum mismatch for " + role + ": expected=" + expectedSha + " actual=" + actual);
        }
    }

    private static String artifactSha(String manifestJson, String role) {
        Pattern pattern = Pattern.compile(
            "\\{\"role\":\"" + Pattern.quote(role) + "\".*?\"sha256\":\"([0-9a-f]{64})\"");
        Matcher matcher = pattern.matcher(manifestJson);
        if (!matcher.find()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                "Missing artifact checksum for role: " + role);
        }
        return matcher.group(1);
    }

    private static String nestedArtifactSha(String manifestJson, String field) {
        String object = requireObject(manifestJson, field);
        return requireString(object, "sha256");
    }

    private static Path requireDirectory(Path path) {
        Path safe = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(safe)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT,
                "Corpus directory does not exist: " + safe);
        }
        return safe;
    }

    private static Path requireFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT,
                "Missing corpus artifact: " + path);
        }
        return path;
    }

    private static String requireString(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        if (!matcher.find()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Missing string field: " + field);
        }
        return matcher.group(1);
    }

    private static String optionalString(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static int requireInt(String json, String field) {
        Matcher matcher = Pattern.compile(
            "\"" + Pattern.quote(field) + "\"\\s*:\\s*(-?[0-9]+)").matcher(json);
        if (!matcher.find()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Missing integer field: " + field);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static String requireObject(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\{");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Missing object field: " + field);
        }
        return extractBalanced(json, matcher.end() - 1, '{', '}');
    }

    private static String requireArrayBody(String json, String field) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\\[");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                "Missing array field: " + field);
        }
        String array = extractBalanced(json, matcher.end() - 1, '[', ']');
        return array.substring(1, array.length() - 1);
    }

    private static String extractBalanced(String json, int openIndex, char open, char close) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = openIndex; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return json.substring(openIndex, i + 1);
                }
            }
        }
        throw new GeneratedCorpusEvaluationException(
            GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
            "Unbalanced JSON for nested value");
    }

    private static List<String> splitTopLevelObjects(String arrayBody) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(arrayBody.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    record LoadedCorpus(
        Path directory,
        GeneratedCorpusProvenance provenance,
        GeneratedCorpusGroundTruth groundTruth,
        Path eventsPath,
        Path annotationsPath
    ) {
    }
}
