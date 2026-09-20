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
import java.util.List;
import java.util.Objects;

/**
 * Deterministic writer for candidate detection-evaluation evidence.
 * <p>
 * Output filenames are distinct from diagnostic evaluation evidence and from
 * Official Detection Reference Baseline artifacts. Existing destinations are refused.
 */
public final class CandidateDetectionEvaluationEvidenceWriter {
    static final String JSON_FILE_NAME = "candidate-evaluation.json";
    static final String MARKDOWN_FILE_NAME = "candidate-evaluation.md";

    private final CandidateDetectionEvaluationEvidenceValidator validator =
        new CandidateDetectionEvaluationEvidenceValidator();

    public WrittenEvidence write(Path outputDirectory, CandidateDetectionEvaluationEvidence evidence) throws IOException {
        Path targetDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory").toAbsolutePath().normalize();
        CandidateDetectionEvaluationRunner.rejectOfficialBaselineDirectory(targetDirectory);
        if (Files.exists(targetDirectory)) {
            throw new FileAlreadyExistsException(targetDirectory.toString(), null, "candidate evaluation evidence directory already exists");
        }
        Path parent = targetDirectory.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("outputDirectory parent is required");
        }
        Files.createDirectories(parent);

        String json = CandidateDetectionEvaluationEvidenceJson.write(evidence) + "\n";
        String markdown = CandidateDetectionEvaluationEvidenceMarkdown.write(evidence) + "\n";
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
     * Deterministic write summary for completed candidate evidence output.
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

final class CandidateDetectionEvaluationEvidenceJson {

    private CandidateDetectionEvaluationEvidenceJson() {
    }

    static String write(CandidateDetectionEvaluationEvidence evidence) {
        CandidateDetectionEvaluationEvidence safe = Objects.requireNonNull(evidence, "evidence");
        StringBuilder json = new StringBuilder(8_192);
        json.append('{');
        appendString(json, "evidenceSchemaVersion", safe.evidenceSchemaVersion(), true);
        appendString(json, "reportKind", safe.reportKind(), false);
        appendString(json, "status", safe.status().name(), false);
        json.append(",\"candidate\":");
        appendCandidate(json, safe.candidate());
        json.append(",\"classification\":{");
        appendNumber(json, "anomalyThreshold", safe.classification().anomalyThreshold(), true);
        appendString(json, "thresholdBoundary", safe.classification().thresholdBoundary(), false);
        json.append('}');
        json.append(",\"loadIssues\":[");
        List<CandidateLoadIssueRecord> issues = safe.loadIssues();
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('{');
            appendString(json, "code", issues.get(i).code().name(), true);
            appendString(json, "message", issues.get(i).message(), false);
            json.append('}');
        }
        json.append(']');
        json.append(",\"evaluation\":");
        if (safe.evaluation().isPresent()) {
            json.append(DetectionEvaluationEvidenceJson.write(safe.evaluation().orElseThrow()));
        } else {
            json.append("null");
        }
        json.append(",\"acceptance\":");
        appendAcceptance(json, safe.acceptance());
        json.append(",\"limitations\":[");
        List<String> limitations = safe.limitations();
        for (int i = 0; i < limitations.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(limitations.get(i))).append('"');
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendCandidate(StringBuilder json, CandidateEvaluationProvenance candidate) {
        json.append('{');
        appendString(json, "scorerId", candidate.scorerId(), true);
        appendString(json, "scorerVersion", candidate.scorerVersion(), false);
        appendString(json, "artifactId", candidate.artifactId(), false);
        appendString(json, "artifactFormat", candidate.artifactFormat(), false);
        appendString(json, "scorerType", candidate.scorerType(), false);
        appendString(json, "artifactDigestAlgorithm", candidate.artifactDigestAlgorithm(), false);
        appendString(json, "artifactDigestHex", candidate.artifactDigestHex(), false);
        appendBoolean(json, "artifactBytesVerified", candidate.artifactBytesVerified(), false);
        appendString(json, "verifiedDigestHex", candidate.verifiedDigestHex(), false);
        appendString(json, "featureSchemaVersion", candidate.featureSchemaVersion(), false);
        appendString(json, "requiredProjection", candidate.requiredProjection(), false);
        appendNumber(json, "requiredFeatureNameCount", candidate.requiredFeatureNames().size(), false);
        appendNumber(json, "declaredFeatureDimension", candidate.declaredFeatureDimension(), false);
        appendString(json, "configurationFingerprintSha256Hex", candidate.configurationFingerprintSha256Hex(), false);
        appendString(json, "runtimeImplementationId", candidate.runtimeImplementationId(), false);
        json.append('}');
    }

    private static void appendAcceptance(StringBuilder json, CandidateEvaluationAcceptanceAssessment acceptance) {
        json.append('{');
        appendString(json, "status", acceptance.status().name(), true);
        json.append(",\"policy\":");
        if (acceptance.configuredPolicy().isEmpty()) {
            json.append("null");
        } else {
            appendPolicy(json, acceptance.configuredPolicy().orElseThrow());
        }
        json.append(",\"issues\":[");
        List<CandidateEvaluationAcceptanceIssue> issues = acceptance.issues();
        for (int i = 0; i < issues.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            CandidateEvaluationAcceptanceIssue issue = issues.get(i);
            json.append('{');
            appendString(json, "code", issue.code().name(), true);
            appendString(json, "actual", issue.actual(), false);
            appendString(json, "required", issue.required(), false);
            appendString(json, "message", issue.message(), false);
            json.append('}');
        }
        json.append("]}");
    }

    private static void appendPolicy(StringBuilder json, CandidateEvaluationAcceptancePolicy policy) {
        json.append('{');
        boolean first = true;
        first = appendOptionalLong(json, "minimumEvaluableObservations", policy.minimumEvaluableObservations(), first);
        first = appendOptionalLong(json, "maximumExcludedObservations", policy.maximumExcludedObservations(), first);
        first = appendOptionalDouble(json, "minimumPrecision", policy.minimumPrecision(), first);
        first = appendOptionalDouble(json, "minimumRecall", policy.minimumRecall(), first);
        first = appendOptionalDouble(json, "minimumF1", policy.minimumF1(), first);
        first = appendOptionalDouble(json, "maximumFalsePositiveRate", policy.maximumFalsePositiveRate(), first);
        appendOptionalDouble(json, "maximumFalseNegativeRate", policy.maximumFalseNegativeRate(), first);
        json.append('}');
    }

    private static boolean appendOptionalLong(StringBuilder json, String field, java.util.Optional<Long> value, boolean first) {
        if (value.isEmpty()) {
            return first;
        }
        appendNumber(json, field, value.orElseThrow(), first);
        return false;
    }

    private static boolean appendOptionalDouble(StringBuilder json, String field, java.util.Optional<Double> value, boolean first) {
        if (value.isEmpty()) {
            return first;
        }
        appendNumber(json, field, value.orElseThrow(), first);
        return false;
    }

    private static void appendNumber(StringBuilder json, String field, long value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":").append(value);
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":\"").append(escape(value == null ? "" : value)).append('"');
    }

    private static void appendBoolean(StringBuilder json, String field, boolean value, boolean first) {
        json.append(first ? "" : ",");
        json.append('"').append(escape(field)).append("\":").append(value);
    }

    private static void appendNumber(StringBuilder json, String field, int value, boolean first) {
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

final class CandidateDetectionEvaluationEvidenceMarkdown {

    private CandidateDetectionEvaluationEvidenceMarkdown() {
    }

    static String write(CandidateDetectionEvaluationEvidence evidence) {
        CandidateDetectionEvaluationEvidence safe = Objects.requireNonNull(evidence, "evidence");
        StringBuilder markdown = new StringBuilder(8_192);
        markdown.append("# Candidate Detection Evaluation Evidence\n\n");
        markdown.append("This report records deterministic candidate evaluation evidence. ");
        markdown.append("It does not approve a candidate, enable shadow scoring, select a champion, or mutate the Official Detection Reference Baseline.\n\n");
        markdown.append("## Status\n\n");
        appendBullet(markdown, "Status", safe.status().name());
        appendBullet(markdown, "Report kind", safe.reportKind());
        appendBullet(markdown, "Evidence schema version", safe.evidenceSchemaVersion());
        markdown.append('\n');

        markdown.append("## Classification\n\n");
        appendBullet(markdown, "Reference classification threshold", Double.toString(safe.classification().anomalyThreshold()));
        appendBullet(markdown, "Boundary", safe.classification().thresholdBoundary());
        markdown.append("- This threshold is the fixed reference classification threshold for this evaluation. It is not a production, enforcement, optimal, or recommended deployment threshold.\n\n");

        markdown.append("## Candidate Provenance\n\n");
        appendBullet(markdown, "Scorer ID", blankAsNone(safe.candidate().scorerId()));
        appendBullet(markdown, "Scorer version", blankAsNone(safe.candidate().scorerVersion()));
        appendBullet(markdown, "Artifact ID", blankAsNone(safe.candidate().artifactId()));
        appendBullet(markdown, "Artifact format", blankAsNone(safe.candidate().artifactFormat()));
        appendBullet(markdown, "Scorer type", blankAsNone(safe.candidate().scorerType()));
        appendBullet(markdown, "Artifact digest algorithm", blankAsNone(safe.candidate().artifactDigestAlgorithm()));
        appendBullet(markdown, "Artifact digest", blankAsNone(safe.candidate().artifactDigestHex()));
        appendBullet(markdown, "Artifact bytes verified", Boolean.toString(safe.candidate().artifactBytesVerified()));
        appendBullet(markdown, "Verified digest", blankAsNone(safe.candidate().verifiedDigestHex()));
        appendBullet(markdown, "Feature schema version", blankAsNone(safe.candidate().featureSchemaVersion()));
        appendBullet(markdown, "Required projection", blankAsNone(safe.candidate().requiredProjection()));
        appendBullet(markdown, "Required feature name count", Integer.toString(safe.candidate().requiredFeatureNames().size()));
        appendBullet(markdown, "Declared feature dimension", Integer.toString(safe.candidate().declaredFeatureDimension()));
        appendBullet(markdown, "Configuration fingerprint", blankAsNone(safe.candidate().configurationFingerprintSha256Hex()));
        appendBullet(markdown, "Runtime implementation", blankAsNone(safe.candidate().runtimeImplementationId()));
        markdown.append("- CONFIGURATION FINGERPRINT is not ARTIFACT DIGEST.\n\n");

        markdown.append("## Load Issues\n\n");
        if (safe.loadIssues().isEmpty()) {
            markdown.append("- None. Candidate loading succeeded before evaluation.\n\n");
        } else {
            for (CandidateLoadIssueRecord issue : safe.loadIssues()) {
                markdown.append("- `").append(markdownCode(issue.code().name())).append("`: ")
                    .append(markdownText(issue.message())).append('\n');
            }
            markdown.append("- CANDIDATE LOAD FAILURE is not a detector prediction and is not an attack.\n\n");
        }

        markdown.append("## Nested Evaluation Evidence\n\n");
        if (safe.evaluation().isPresent()) {
            markdown.append("The nested diagnostic evaluation evidence below was produced by the existing Detection Evaluation Framework. ");
            markdown.append("It is candidate-specific and is not Official Detection Reference Baseline evidence.\n\n");
            markdown.append(DetectionEvaluationEvidenceMarkdown.write(safe.evaluation().orElseThrow()));
            markdown.append('\n');
        } else {
            markdown.append("Replay and evaluation were not executed because the candidate was not operationally READY. ");
            markdown.append("No detector predictions were fabricated.\n\n");
        }

        markdown.append("## Acceptance Assessment\n\n");
        appendBullet(markdown, "Acceptance status", safe.acceptance().status().name());
        markdown.append("- EVALUATION RESULT is not ACCEPTANCE DECISION. ACCEPTANCE DECISION is not production approval.\n");
        if (safe.acceptance().configuredPolicy().isEmpty()) {
            markdown.append("- No acceptance policy was supplied. The candidate was not assessed.\n\n");
        } else {
            CandidateEvaluationAcceptancePolicy policy = safe.acceptance().configuredPolicy().orElseThrow();
            markdown.append("- Configured criteria:\n");
            policy.minimumEvaluableObservations().ifPresent(value ->
                markdown.append("  - minimum evaluable observations: `").append(value).append("`\n"));
            policy.maximumExcludedObservations().ifPresent(value ->
                markdown.append("  - maximum excluded observations: `").append(value).append("`\n"));
            policy.minimumPrecision().ifPresent(value ->
                markdown.append("  - minimum precision: `").append(Double.toString(value)).append("`\n"));
            policy.minimumRecall().ifPresent(value ->
                markdown.append("  - minimum recall: `").append(Double.toString(value)).append("`\n"));
            policy.minimumF1().ifPresent(value ->
                markdown.append("  - minimum F1: `").append(Double.toString(value)).append("`\n"));
            policy.maximumFalsePositiveRate().ifPresent(value ->
                markdown.append("  - maximum false-positive rate: `").append(Double.toString(value)).append("`\n"));
            policy.maximumFalseNegativeRate().ifPresent(value ->
                markdown.append("  - maximum false-negative rate: `").append(Double.toString(value)).append("`\n"));
            markdown.append('\n');
        }
        if (safe.acceptance().issues().isEmpty()) {
            if (safe.acceptance().status() == CandidateEvaluationAcceptanceStatus.ACCEPTED) {
                markdown.append("- All configured criteria were satisfied. This is not champion selection or production deployment.\n\n");
            } else {
                markdown.append("- No acceptance issues. Assessment was not performed or no criteria failed.\n\n");
            }
        } else {
            markdown.append("- Failed criteria:\n");
            for (CandidateEvaluationAcceptanceIssue issue : safe.acceptance().issues()) {
                markdown.append("  - `").append(markdownCode(issue.code().name())).append("`: actual `")
                    .append(markdownCode(issue.actual())).append("`, required `")
                    .append(markdownCode(issue.required())).append("` — ")
                    .append(markdownText(issue.message())).append('\n');
            }
            markdown.append("- ACCEPTANCE REJECTED is not an evaluation infrastructure failure and is not an attack.\n\n");
        }

        markdown.append("## Limitations\n\n");
        for (String limitation : safe.limitations()) {
            markdown.append("- ").append(markdownText(limitation)).append('\n');
        }
        return markdown.toString();
    }

    private static void appendBullet(StringBuilder markdown, String label, String value) {
        markdown.append("- ").append(label).append(": `").append(markdownCode(value)).append("`\n");
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
