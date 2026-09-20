package dev.aisentinel.core.evaluation;

import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic writer for machine-readable and human-readable evaluation evidence.
 */
public final class DetectionEvaluationEvidenceWriter {
    static final String JSON_FILE_NAME = "evaluation.json";
    static final String MARKDOWN_FILE_NAME = "evaluation.md";

    private final DetectionEvaluationEvidenceValidator validator = new DetectionEvaluationEvidenceValidator();

    public WrittenEvidence write(Path outputDirectory, DetectionEvaluationEvidence evidence) throws IOException {
        Path targetDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        if (Files.exists(targetDirectory)) {
            throw new FileAlreadyExistsException(targetDirectory.toString(), null, "evaluation evidence directory already exists");
        }
        Path parent = targetDirectory.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("outputDirectory parent is required");
        }
        Files.createDirectories(parent);

        String json = DetectionEvaluationEvidenceJson.write(evidence) + "\n";
        String markdown = DetectionEvaluationEvidenceMarkdown.write(evidence) + "\n";
        validator.validateArtifacts(evidence, json, markdown);

        Path tempDirectory = Files.createTempDirectory(parent, targetDirectory.getFileName().toString() + ".tmp-");
        try {
            Path jsonPath = tempDirectory.resolve(JSON_FILE_NAME);
            Path markdownPath = tempDirectory.resolve(MARKDOWN_FILE_NAME);
            Files.writeString(jsonPath, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(markdownPath, markdown, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            moveDirectory(tempDirectory, targetDirectory);
            byte[] jsonBytes = Files.readAllBytes(targetDirectory.resolve(JSON_FILE_NAME));
            byte[] markdownBytes = Files.readAllBytes(targetDirectory.resolve(MARKDOWN_FILE_NAME));
            return new WrittenEvidence(
                targetDirectory,
                TrainingFingerprintHashes.sha256HexBytes(jsonBytes),
                TrainingFingerprintHashes.sha256HexBytes(markdownBytes),
                jsonBytes.length,
                markdownBytes.length
            );
        } catch (IOException | RuntimeException e) {
            deleteRecursively(tempDirectory);
            throw e;
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(candidate -> {
                    try {
                        Files.deleteIfExists(candidate);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    /**
     * Deterministic write summary for completed evidence output.
     */
    public record WrittenEvidence(
        Path outputDirectory,
        String jsonSha256,
        String markdownSha256,
        long jsonBytes,
        long markdownBytes
    ) {
        public WrittenEvidence {
            outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
            if (jsonSha256 == null || !jsonSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("jsonSha256 must be 64 lowercase hex characters");
            }
            if (markdownSha256 == null || !markdownSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("markdownSha256 must be 64 lowercase hex characters");
            }
            if (jsonBytes < 0L || markdownBytes < 0L) {
                throw new IllegalArgumentException("artifact sizes must be >= 0");
            }
        }
    }
}

final class DetectionEvaluationEvidenceJson {

    private DetectionEvaluationEvidenceJson() {
    }

    static String write(DetectionEvaluationEvidence evidence) {
        DetectionEvaluationEvidence safeEvidence = Objects.requireNonNull(evidence, "evidence");
        StringBuilder json = new StringBuilder(32_768);
        json.append('{');
        appendString(json, "evidenceSchemaVersion", safeEvidence.evidenceSchemaVersion(), true);
        appendString(json, "reportKind", safeEvidence.reportKind(), false);
        json.append(",\"reference\":");
        appendReference(json, safeEvidence.reference());
        json.append(",\"replay\":");
        appendReplay(json, safeEvidence.replay());
        json.append(",\"classification\":");
        appendClassification(json, safeEvidence.classification());
        json.append(",\"counts\":");
        appendCounts(json, safeEvidence.counts());
        json.append(",\"confusionMatrix\":");
        appendConfusionMatrix(json, safeEvidence.metrics().confusionMatrix());
        json.append(",\"metrics\":");
        appendMetrics(json, safeEvidence.metrics().metrics());
        json.append(",\"scenarioMetrics\":[");
        for (int i = 0; i < safeEvidence.metrics().scenarios().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendScenarioMetrics(json, safeEvidence.metrics().scenarios().get(i));
        }
        json.append(']');
        json.append(",\"temporal\":");
        appendTemporal(json, safeEvidence.temporal());
        appendStringArray(json, "limitations", safeEvidence.limitations(), false);
        json.append('}');
        return json.toString();
    }

    private static void appendReference(StringBuilder json, DetectionEvaluationEvidence.ReferenceProvenance reference) {
        json.append('{');
        appendString(json, "datasetId", reference.datasetId(), true);
        appendString(json, "datasetSchemaVersion", reference.datasetSchemaVersion(), false);
        appendString(json, "evaluationEventSchemaVersion", reference.evaluationEventSchemaVersion(), false);
        appendString(json, "featureSchemaVersion", reference.featureSchemaVersion(), false);
        appendString(json, "annotationSchemaVersion", reference.annotationSchemaVersion(), false);
        appendString(json, "sourceClassification", reference.sourceClassification(), false);
        appendString(json, "transformationVersion", reference.transformationVersion(), false);
        appendString(json, "ordering", reference.ordering(), false);
        appendString(json, "eventsSha256", reference.eventsSha256(), false);
        json.append('}');
    }

    private static void appendReplay(StringBuilder json, DetectionEvaluationEvidence.ReplayProvenance replay) {
        json.append('{');
        appendString(json, "replaySchemaVersion", replay.replaySchemaVersion(), true);
        appendString(json, "replayRunId", replay.replayRunId(), false);
        appendString(json, "datasetId", replay.datasetId(), false);
        appendString(json, "replayMode", replay.replayMode(), false);
        appendString(json, "scorerId", replay.scorerId(), false);
        appendOptionalString(json, "scorerVersion", replay.scorerVersion());
        appendString(json, "policyId", replay.policyId(), false);
        appendOptionalString(json, "policyVersion", replay.policyVersion());
        appendString(json, "configurationFingerprint", replay.configurationFingerprint(), false);
        appendString(json, "aiSentinelVersion", replay.aiSentinelVersion(), false);
        appendString(json, "resultsSha256", replay.resultsSha256(), false);
        json.append('}');
    }

    private static void appendClassification(StringBuilder json, DetectionEvaluationEvidence.ClassificationProvenance classification) {
        json.append('{');
        appendNumber(json, "anomalyThreshold", classification.anomalyThreshold(), true);
        appendString(json, "thresholdBoundary", classification.thresholdBoundary(), false);
        json.append('}');
    }

    private static void appendCounts(StringBuilder json, DetectionEvaluationEvidence.StructuralCounts counts) {
        json.append('{');
        appendNumber(json, "referenceEventCount", counts.referenceEventCount(), true);
        appendNumber(json, "scenarioCount", counts.scenarioCount(), false);
        appendNumber(json, "alignedObservationCount", counts.alignedObservationCount(), false);
        appendNumber(json, "expectedNormalObservationCount", counts.expectedNormalObservationCount(), false);
        appendNumber(json, "expectedAnomalousObservationCount", counts.expectedAnomalousObservationCount(), false);
        appendNumber(json, "evaluablePredictionCount", counts.evaluablePredictionCount(), false);
        appendNumber(json, "excludedPredictionCount", counts.excludedPredictionCount(), false);
        appendNumber(json, "anomalySegmentCount", counts.anomalySegmentCount(), false);
        appendNumber(json, "detectedSegmentCount", counts.detectedSegmentCount(), false);
        appendNumber(json, "undetectedSegmentCount", counts.undetectedSegmentCount(), false);
        appendNumber(json, "recoveryWindowCount", counts.recoveryWindowCount(), false);
        appendNumber(json, "stabilizedRecoveryCount", counts.stabilizedRecoveryCount(), false);
        appendNumber(json, "unstabilizedRecoveryCount", counts.unstabilizedRecoveryCount(), false);
        json.append('}');
    }

    private static void appendConfusionMatrix(StringBuilder json, DetectionConfusionMatrix matrix) {
        json.append('{');
        appendNumber(json, "truePositives", matrix.truePositives(), true);
        appendNumber(json, "trueNegatives", matrix.trueNegatives(), false);
        appendNumber(json, "falsePositives", matrix.falsePositives(), false);
        appendNumber(json, "falseNegatives", matrix.falseNegatives(), false);
        json.append('}');
    }

    private static void appendMetrics(StringBuilder json, DetectionMetrics metrics) {
        json.append('{');
        appendMetricValue(json, "precision", metrics.precision(), true);
        appendMetricValue(json, "recall", metrics.recall(), false);
        appendMetricValue(json, "f1", metrics.f1(), false);
        appendMetricValue(json, "falsePositiveRate", metrics.falsePositiveRate(), false);
        appendMetricValue(json, "falseNegativeRate", metrics.falseNegativeRate(), false);
        json.append('}');
    }

    private static void appendScenarioMetrics(StringBuilder json, ScenarioDetectionMetrics scenario) {
        json.append('{');
        appendString(json, "scenarioId", scenario.scenarioId(), true);
        appendString(json, "scenarioCategory", scenario.scenarioCategory().name(), false);
        appendNumber(json, "totalObservationCount", scenario.totalObservationCount(), false);
        appendNumber(json, "evaluablePredictionCount", scenario.evaluablePredictionCount(), false);
        appendNumber(json, "excludedPredictionCount", scenario.excludedPredictionCount(), false);
        json.append(",\"confusionMatrix\":");
        appendConfusionMatrix(json, scenario.confusionMatrix());
        json.append(",\"metrics\":");
        appendMetrics(json, scenario.metrics());
        json.append('}');
    }

    private static void appendTemporal(StringBuilder json, TemporalDetectionEvaluation temporal) {
        json.append('{');
        appendNumber(json, "scenarioCount", temporal.scenarios().size(), true);
        json.append(",\"scenarios\":[");
        for (int i = 0; i < temporal.scenarios().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendTemporalScenario(json, temporal.scenarios().get(i));
        }
        json.append("]}");
    }

    private static void appendTemporalScenario(StringBuilder json, ScenarioTemporalEvaluation scenario) {
        json.append('{');
        appendString(json, "scenarioId", scenario.scenarioId(), true);
        appendString(json, "scenarioCategory", scenario.scenarioCategory().name(), false);
        appendNumber(json, "observationCount", scenario.observationCount(), false);
        json.append(",\"anomalySegments\":[");
        for (int i = 0; i < scenario.anomalySegments().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendSegment(json, scenario.anomalySegments().get(i));
        }
        json.append("]}");
    }

    private static void appendSegment(StringBuilder json, TemporalAnomalySegment segment) {
        json.append('{');
        appendNumber(json, "segmentIndex", segment.segmentIndex(), true);
        json.append(",\"anomalyOnset\":");
        appendPoint(json, segment.anomalyOnset());
        json.append(",\"anomalyWindowEnd\":");
        appendPoint(json, segment.anomalyWindowEnd());
        appendNumber(json, "anomalyObservationCount", segment.anomalyObservationCount(), false);
        appendBoolean(json, "detected", segment.detected(), false);
        appendNullablePoint(json, "firstDetection", segment.firstDetection());
        appendNullableInteger(json, "detectionObservationDelay", segment.detectionObservationDelay());
        appendNullableInteger(json, "evaluableObservationDelay", segment.evaluableObservationDelay());
        appendNullableDuration(json, "detectionTimeDelay", segment.detectionTimeDelay());
        appendNumber(json, "unavailableObservationCount", segment.unavailableObservationCount(), false);
        appendNullableRecovery(json, "recovery", segment.recovery());
        json.append('}');
    }

    private static void appendPoint(StringBuilder json, TemporalObservationPoint point) {
        json.append('{');
        appendString(json, "eventId", point.eventId(), true);
        appendNumber(json, "sequenceNumber", point.sequenceNumber(), false);
        appendString(json, "observedAt", point.observedAt().toString(), false);
        json.append('}');
    }

    private static void appendNullablePoint(StringBuilder json, String field, TemporalObservationPoint point) {
        json.append(",\"").append(escape(field)).append("\":");
        if (point == null) {
            json.append("null");
            return;
        }
        appendPoint(json, point);
    }

    private static void appendNullableRecovery(StringBuilder json, String field, TemporalRecoveryEvaluation recovery) {
        json.append(",\"").append(escape(field)).append("\":");
        if (recovery == null) {
            json.append("null");
            return;
        }
        json.append('{');
        appendPoint(json, "recoveryOnset", recovery.recoveryOnset(), true);
        appendPoint(json, "recoveryWindowEnd", recovery.recoveryWindowEnd(), false);
        appendNumber(json, "recoveryObservationCount", recovery.recoveryObservationCount(), false);
        appendBoolean(json, "stabilized", recovery.stabilized(), false);
        appendNullablePoint(json, "firstStableNormalPrediction", recovery.firstStableNormalPrediction());
        appendNullableInteger(json, "recoveryObservationDelay", recovery.recoveryObservationDelay());
        appendNullableInteger(json, "evaluableRecoveryObservationDelay", recovery.evaluableRecoveryObservationDelay());
        appendNullableDuration(json, "recoveryTimeDelay", recovery.recoveryTimeDelay());
        appendNumber(json, "unavailableObservationCount", recovery.unavailableObservationCount(), false);
        json.append('}');
    }

    private static void appendPoint(StringBuilder json, String field, TemporalObservationPoint point, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":");
        appendPoint(json, point);
    }

    private static void appendMetricValue(StringBuilder json, String field, DetectionMetricValue value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":{");
        appendBoolean(json, "defined", value.defined(), true);
        if (value.defined()) {
            appendNumber(json, "value", Objects.requireNonNull(value.value(), "value"), false);
        } else {
            json.append(",\"value\":null");
        }
        json.append('}');
    }

    private static void appendNullableInteger(StringBuilder json, String field, Integer value) {
        json.append(",\"").append(escape(field)).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            json.append(value.intValue());
        }
    }

    private static void appendNullableDuration(StringBuilder json, String field, Duration value) {
        json.append(",\"").append(escape(field)).append("\":");
        if (value == null) {
            json.append("null");
        } else {
            json.append('"').append(escape(value.toString())).append('"');
        }
    }

    private static void appendStringArray(StringBuilder json, String field, List<String> values, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(values.get(i))).append('"');
        }
        json.append(']');
    }

    private static void appendOptionalString(StringBuilder json, String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        json.append(",\"").append(escape(field)).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendBoolean(StringBuilder json, String field, boolean value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":").append(value);
    }

    private static void appendNumber(StringBuilder json, String field, int value, boolean first) {
        appendNumber(json, field, (long) value, first);
    }

    private static void appendNumber(StringBuilder json, String field, long value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":").append(value);
    }

    private static void appendNumber(StringBuilder json, String field, double value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":").append(Double.toString(value));
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
                        out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}

final class DetectionEvaluationEvidenceMarkdown {

    private DetectionEvaluationEvidenceMarkdown() {
    }

    static String write(DetectionEvaluationEvidence evidence) {
        DetectionEvaluationEvidence safeEvidence = Objects.requireNonNull(evidence, "evidence");
        StringBuilder markdown = new StringBuilder(32_768);
        markdown.append("# Detection Evaluation Evidence\n\n");
        markdown.append("This report records deterministic diagnostic evaluation evidence. It does not establish an official detection baseline.\n\n");

        markdown.append("## Configuration\n\n");
        markdown.append("- Threshold: `").append(Double.toString(safeEvidence.classification().anomalyThreshold())).append("`\n");
        markdown.append("- Boundary: `").append(safeEvidence.classification().thresholdBoundary()).append("`\n");
        markdown.append("- Policy action determines detection: no\n");
        markdown.append("- Detection delay equals request latency: no\n\n");

        markdown.append("## Reference Provenance\n\n");
        appendBullet(markdown, "Dataset ID", safeEvidence.reference().datasetId());
        appendBullet(markdown, "Dataset schema version", safeEvidence.reference().datasetSchemaVersion());
        appendBullet(markdown, "Evaluation event schema version", safeEvidence.reference().evaluationEventSchemaVersion());
        appendBullet(markdown, "Feature schema version", safeEvidence.reference().featureSchemaVersion());
        appendBullet(markdown, "Annotation schema version", safeEvidence.reference().annotationSchemaVersion());
        appendBullet(markdown, "Source classification", safeEvidence.reference().sourceClassification());
        appendBullet(markdown, "Transformation version", safeEvidence.reference().transformationVersion());
        appendBullet(markdown, "Ordering", safeEvidence.reference().ordering());
        appendBullet(markdown, "Events SHA-256", safeEvidence.reference().eventsSha256());
        markdown.append('\n');

        markdown.append("## Replay Provenance\n\n");
        appendBullet(markdown, "Replay schema version", safeEvidence.replay().replaySchemaVersion());
        appendBullet(markdown, "Replay run ID", safeEvidence.replay().replayRunId());
        appendBullet(markdown, "Replay mode", safeEvidence.replay().replayMode());
        appendBullet(markdown, "Scorer ID", safeEvidence.replay().scorerId());
        appendBullet(markdown, "Scorer version", blankAsNone(safeEvidence.replay().scorerVersion()));
        appendBullet(markdown, "Policy ID", safeEvidence.replay().policyId());
        appendBullet(markdown, "Policy version", blankAsNone(safeEvidence.replay().policyVersion()));
        appendBullet(markdown, "Configuration fingerprint", safeEvidence.replay().configurationFingerprint());
        appendBullet(markdown, "AI-Sentinel version", safeEvidence.replay().aiSentinelVersion());
        appendBullet(markdown, "Replay results SHA-256", safeEvidence.replay().resultsSha256());
        markdown.append('\n');

        markdown.append("## Structural Counts\n\n");
        appendBullet(markdown, "Reference events", Integer.toString(safeEvidence.counts().referenceEventCount()));
        appendBullet(markdown, "Scenarios", Integer.toString(safeEvidence.counts().scenarioCount()));
        appendBullet(markdown, "Aligned evaluation observations", Integer.toString(safeEvidence.counts().alignedObservationCount()));
        appendBullet(markdown, "Expected normal observations", Long.toString(safeEvidence.counts().expectedNormalObservationCount()));
        appendBullet(markdown, "Expected anomalous observations", Long.toString(safeEvidence.counts().expectedAnomalousObservationCount()));
        appendBullet(markdown, "Evaluable predictions", Long.toString(safeEvidence.counts().evaluablePredictionCount()));
        appendBullet(markdown, "Excluded predictions", Long.toString(safeEvidence.counts().excludedPredictionCount()));
        appendBullet(markdown, "Anomalous segments", Integer.toString(safeEvidence.counts().anomalySegmentCount()));
        appendBullet(markdown, "Detected segments", Integer.toString(safeEvidence.counts().detectedSegmentCount()));
        appendBullet(markdown, "Undetected segments", Integer.toString(safeEvidence.counts().undetectedSegmentCount()));
        appendBullet(markdown, "Observed recovery windows", Integer.toString(safeEvidence.counts().recoveryWindowCount()));
        appendBullet(markdown, "Stabilized recovery windows", Integer.toString(safeEvidence.counts().stabilizedRecoveryCount()));
        appendBullet(markdown, "Unstabilized recovery windows", Integer.toString(safeEvidence.counts().unstabilizedRecoveryCount()));
        markdown.append('\n');

        markdown.append("## Confusion Matrix\n\n");
        appendBullet(markdown, "True positives", Long.toString(safeEvidence.metrics().confusionMatrix().truePositives()));
        appendBullet(markdown, "True negatives", Long.toString(safeEvidence.metrics().confusionMatrix().trueNegatives()));
        appendBullet(markdown, "False positives", Long.toString(safeEvidence.metrics().confusionMatrix().falsePositives()));
        appendBullet(markdown, "False negatives", Long.toString(safeEvidence.metrics().confusionMatrix().falseNegatives()));
        markdown.append('\n');

        markdown.append("## Metrics\n\n");
        appendBullet(markdown, "Precision", metric(safeEvidence.metrics().metrics().precision()));
        appendBullet(markdown, "Recall", metric(safeEvidence.metrics().metrics().recall()));
        appendBullet(markdown, "F1", metric(safeEvidence.metrics().metrics().f1()));
        appendBullet(markdown, "False-positive rate", metric(safeEvidence.metrics().metrics().falsePositiveRate()));
        appendBullet(markdown, "False-negative rate", metric(safeEvidence.metrics().metrics().falseNegativeRate()));
        markdown.append('\n');

        markdown.append("## Scenario Metrics\n\n");
        for (ScenarioDetectionMetrics scenario : safeEvidence.metrics().scenarios()) {
            markdown.append("### `").append(markdownCode(scenario.scenarioId())).append("`\n\n");
            appendBullet(markdown, "Category", scenario.scenarioCategory().name());
            appendBullet(markdown, "Total observations", Long.toString(scenario.totalObservationCount()));
            appendBullet(markdown, "Evaluable predictions", Long.toString(scenario.evaluablePredictionCount()));
            appendBullet(markdown, "Excluded predictions", Long.toString(scenario.excludedPredictionCount()));
            appendBullet(markdown, "True positives", Long.toString(scenario.confusionMatrix().truePositives()));
            appendBullet(markdown, "True negatives", Long.toString(scenario.confusionMatrix().trueNegatives()));
            appendBullet(markdown, "False positives", Long.toString(scenario.confusionMatrix().falsePositives()));
            appendBullet(markdown, "False negatives", Long.toString(scenario.confusionMatrix().falseNegatives()));
            appendBullet(markdown, "Precision", metric(scenario.metrics().precision()));
            appendBullet(markdown, "Recall", metric(scenario.metrics().recall()));
            appendBullet(markdown, "F1", metric(scenario.metrics().f1()));
            appendBullet(markdown, "False-positive rate", metric(scenario.metrics().falsePositiveRate()));
            appendBullet(markdown, "False-negative rate", metric(scenario.metrics().falseNegativeRate()));
            markdown.append('\n');
        }

        markdown.append("## Temporal Evidence\n\n");
        for (ScenarioTemporalEvaluation scenario : safeEvidence.temporal().scenarios()) {
            markdown.append("### `").append(markdownCode(scenario.scenarioId())).append("`\n\n");
            appendBullet(markdown, "Category", scenario.scenarioCategory().name());
            appendBullet(markdown, "Scenario observation count", Integer.toString(scenario.observationCount()));
            appendBullet(markdown, "Anomalous segments", Integer.toString(scenario.anomalySegments().size()));
            if (scenario.anomalySegments().isEmpty()) {
                markdown.append("- No anomalous truth segments are present.\n\n");
                continue;
            }
            for (TemporalAnomalySegment segment : scenario.anomalySegments()) {
                markdown.append("#### Segment `").append(segment.segmentIndex()).append("`\n\n");
                appendBullet(markdown, "Anomaly onset event", segment.anomalyOnset().eventId());
                appendBullet(markdown, "Anomaly onset sequence", Integer.toString(segment.anomalyOnset().sequenceNumber()));
                appendBullet(markdown, "Anomaly onset observed at", segment.anomalyOnset().observedAt().toString());
                appendBullet(markdown, "Anomaly window end event", segment.anomalyWindowEnd().eventId());
                appendBullet(markdown, "Anomaly observation count", Integer.toString(segment.anomalyObservationCount()));
                appendBullet(markdown, "Detected within observed anomaly window", segment.detected() ? "yes" : "no");
                appendBullet(markdown, "Unavailable detector evidence count", Long.toString(segment.unavailableObservationCount()));
                if (segment.detected()) {
                    appendBullet(markdown, "First detection event", segment.firstDetection().eventId());
                    appendBullet(markdown, "Detection observation delay", Integer.toString(segment.detectionObservationDelay()));
                    appendBullet(markdown, "Evaluable-opportunity delay", Integer.toString(segment.evaluableObservationDelay()));
                    appendBullet(markdown, "Event-time detection delay", segment.detectionTimeDelay().toString());
                } else {
                    markdown.append("- First detection event: not detected within the observed anomaly window\n");
                }
                appendRecovery(markdown, segment.recovery());
                markdown.append('\n');
            }
        }

        markdown.append("## Limitations\n\n");
        for (String limitation : safeEvidence.limitations()) {
            markdown.append("- ").append(markdownText(limitation)).append('\n');
        }
        return markdown.toString();
    }

    private static void appendRecovery(StringBuilder markdown, TemporalRecoveryEvaluation recovery) {
        if (recovery == null) {
            markdown.append("- Observed recovery window: none\n");
            return;
        }
        markdown.append("- Observed recovery window: present\n");
        appendBullet(markdown, "Recovery onset event", recovery.recoveryOnset().eventId());
        appendBullet(markdown, "Recovery observation count", Integer.toString(recovery.recoveryObservationCount()));
        appendBullet(markdown, "Stable recovery reached", recovery.stabilized() ? "yes" : "no");
        appendBullet(markdown, "Unavailable recovery evidence count", Long.toString(recovery.unavailableObservationCount()));
        if (recovery.stabilized()) {
            appendBullet(markdown, "First stable normal prediction event", recovery.firstStableNormalPrediction().eventId());
            appendBullet(markdown, "Recovery observation delay", Integer.toString(recovery.recoveryObservationDelay()));
            appendBullet(markdown, "Evaluable recovery delay", Integer.toString(recovery.evaluableRecoveryObservationDelay()));
            appendBullet(markdown, "Recovery time delay", recovery.recoveryTimeDelay().toString());
        } else {
            markdown.append("- First stable normal prediction event: none within the observed recovery window\n");
        }
    }

    private static void appendBullet(StringBuilder markdown, String label, String value) {
        markdown.append("- ").append(label).append(": `").append(markdownCode(value)).append("`\n");
    }

    private static String metric(DetectionMetricValue value) {
        return value.defined() ? Double.toString(Objects.requireNonNull(value.value(), "value")) : "undefined";
    }

    private static String blankAsNone(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private static String markdownCode(String value) {
        return Objects.requireNonNull(value, "value")
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("`", "\\`")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }

    private static String markdownText(String value) {
        return Objects.requireNonNull(value, "value")
            .replace("\\", "\\\\")
            .replace("|", "\\|")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }
}
