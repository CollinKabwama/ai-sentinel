package dev.aisentinel.core.replay;

import dev.aisentinel.core.contract.ContractRiskFactor;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Deterministic JSONL writer for replay output.
 */
public final class ReplayRunWriter implements AutoCloseable {

    private final Path outputDirectory;
    private final Path resultsTempFile;
    private final Path manifestTempFile;
    private final BufferedWriter resultsWriter;
    private final ReplayRunManifestBuilder manifestBuilder;
    private long resultCount;
    private boolean closed;

    public ReplayRunWriter(Path outputDirectory, ReplayRunManifestBuilder manifestBuilder) throws IOException {
        this.outputDirectory = outputDirectory;
        this.manifestBuilder = manifestBuilder;
        Files.createDirectories(outputDirectory);
        Path resultsFile = outputDirectory.resolve(ReplaySchemas.RESULTS_FILE_NAME);
        Path manifestFile = outputDirectory.resolve(ReplaySchemas.MANIFEST_FILE_NAME);
        if (Files.exists(resultsFile) || Files.exists(manifestFile)) {
            throw new FileAlreadyExistsException(outputDirectory.toString(), null,
                "replay output already contains finalized artifacts");
        }
        this.resultsTempFile = outputDirectory.resolve(ReplaySchemas.RESULTS_FILE_NAME + ".tmp");
        this.manifestTempFile = outputDirectory.resolve(ReplaySchemas.MANIFEST_FILE_NAME + ".tmp");
        Files.deleteIfExists(resultsTempFile);
        Files.deleteIfExists(manifestTempFile);
        this.resultsWriter = Files.newBufferedWriter(resultsTempFile, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public void append(ReplayResult result) {
        if (closed) {
            throw new IllegalStateException("writer already closed");
        }
        try {
            resultsWriter.write(ReplayResultJson.writeResult(result));
            resultsWriter.write('\n');
            resultCount++;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append replay result", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            resultsWriter.close();
            String resultsSha256 = TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(resultsTempFile));
            ReplayRunManifest manifest = manifestBuilder.build(resultCount, resultsSha256);
            Files.writeString(
                manifestTempFile,
                ReplayResultJson.writeManifest(manifest) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            Files.move(resultsTempFile, outputDirectory.resolve(ReplaySchemas.RESULTS_FILE_NAME),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.move(manifestTempFile, outputDirectory.resolve(ReplaySchemas.MANIFEST_FILE_NAME),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(resultsTempFile);
            Files.deleteIfExists(manifestTempFile);
            throw e;
        }
    }

    public record ReplayRunManifestBuilder(
        String replayRunId,
        String datasetId,
        String datasetEventsSha256,
        String datasetSchemaVersion,
        String evaluationEventSchemaVersion,
        String featureSchemaVersion,
        String annotationSchemaVersion,
        String scorerId,
        String scorerVersion,
        String policyId,
        String policyVersion,
        String configurationFingerprint,
        String replayMode,
        String ordering,
        String aiSentinelVersion
    ) {
        ReplayRunManifest build(long eventCount, String resultsSha256) {
            return new ReplayRunManifest(
                ReplaySchemas.REPLAY_SCHEMA_VERSION,
                replayRunId,
                datasetId,
                datasetEventsSha256,
                datasetSchemaVersion,
                evaluationEventSchemaVersion,
                featureSchemaVersion,
                annotationSchemaVersion,
                scorerId,
                scorerVersion,
                policyId,
                policyVersion,
                configurationFingerprint,
                replayMode,
                ordering,
                eventCount,
                aiSentinelVersion,
                ReplaySchemas.RESULTS_FILE_NAME,
                resultsSha256
            );
        }
    }
}

final class ReplayResultJson {

    private ReplayResultJson() {
    }

    static String writeResult(ReplayResult result) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendString(json, "replaySchemaVersion", result.replaySchemaVersion(), true);
        appendString(json, "replayRunId", result.replayRunId(), false);
        appendString(json, "replayStatus", result.replayStatus().name(), false);
        appendNumber(json, "sequenceNumber", result.sequenceNumber(), false);
        appendString(json, "eventId", result.eventId(), false);
        appendOptionalString(json, "correlationId", result.correlationId());
        appendString(json, "identityKey", result.identityKey(), false);
        appendOptionalString(json, "identityType", result.identityType());
        appendString(json, "observedAt", result.observedAt().toString(), false);
        appendString(json, "endpointKey", result.endpointKey(), false);
        appendString(json, "featureSchemaVersion", result.featureSchemaVersion(), false);
        appendString(json, "scorerId", result.scorerId(), false);
        appendOptionalString(json, "scorerVersion", result.scorerVersion());
        appendOptionalNumber(json, "anomalyScore", result.anomalyScore());
        appendOptionalNumber(json, "policyScore", result.policyScore());
        appendString(json, "action", result.action().name(), false);
        appendStringArray(json, "evaluationStatuses", result.evaluationStatuses().stream().map(Enum::name).toList(), false);
        json.append(",\"riskFactors\":[");
        appendRiskFactors(json, result.riskFactors());
        json.append(']');
        appendOptionalString(json, "policyId", result.policyId());
        appendOptionalString(json, "policyVersion", result.policyVersion());
        appendOptionalString(json, "evaluationMode", result.evaluationMode());
        json.append('}');
        return json.toString();
    }

    static String writeManifest(ReplayRunManifest manifest) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendString(json, "replaySchemaVersion", manifest.replaySchemaVersion(), true);
        appendString(json, "replayRunId", manifest.replayRunId(), false);
        appendString(json, "datasetId", manifest.datasetId(), false);
        appendString(json, "datasetEventsSha256", manifest.datasetEventsSha256(), false);
        appendString(json, "datasetSchemaVersion", manifest.datasetSchemaVersion(), false);
        appendString(json, "evaluationEventSchemaVersion", manifest.evaluationEventSchemaVersion(), false);
        appendString(json, "featureSchemaVersion", manifest.featureSchemaVersion(), false);
        appendOptionalString(json, "annotationSchemaVersion", manifest.annotationSchemaVersion());
        appendString(json, "scorerId", manifest.scorerId(), false);
        appendOptionalString(json, "scorerVersion", manifest.scorerVersion());
        appendString(json, "policyId", manifest.policyId(), false);
        appendOptionalString(json, "policyVersion", manifest.policyVersion());
        appendString(json, "configurationFingerprint", manifest.configurationFingerprint(), false);
        appendString(json, "replayMode", manifest.replayMode(), false);
        appendString(json, "ordering", manifest.ordering(), false);
        appendNumber(json, "eventCount", manifest.eventCount(), false);
        appendString(json, "aiSentinelVersion", manifest.aiSentinelVersion(), false);
        appendString(json, "resultsFile", manifest.resultsFile(), false);
        appendString(json, "resultsSha256", manifest.resultsSha256(), false);
        json.append('}');
        return json.toString();
    }

    private static void appendRiskFactors(StringBuilder json, List<ContractRiskFactor> factors) {
        for (int i = 0; i < factors.size(); i++) {
            ContractRiskFactor factor = factors.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append('{');
            appendString(json, "code", factor.code(), true);
            appendString(json, "category", factor.category(), false);
            appendString(json, "severity", factor.severity(), false);
            appendNumber(json, "contribution", factor.contribution(), false);
            appendNumber(json, "confidence", factor.confidence(), false);
            appendOptionalString(json, "evidenceRef", factor.evidenceRef());
            appendOptionalString(json, "explanation", factor.explanation());
            appendOptionalString(json, "source", factor.source());
            json.append('}');
        }
    }

    private static void appendStringArray(StringBuilder json, String field, List<String> values, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(ReplayJsonSupport.escape(field)).append("\":[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(ReplayJsonSupport.escape(values.get(i))).append('"');
        }
        json.append(']');
    }

    private static void appendOptionalString(StringBuilder json, String field, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        json.append(",\"").append(ReplayJsonSupport.escape(field)).append("\":\"")
            .append(ReplayJsonSupport.escape(value)).append('"');
    }

    private static void appendString(StringBuilder json, String field, String value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(ReplayJsonSupport.escape(field)).append("\":\"")
            .append(ReplayJsonSupport.escape(value)).append('"');
    }

    private static void appendOptionalNumber(StringBuilder json, String field, Double value) {
        if (value == null) {
            return;
        }
        json.append(",\"").append(ReplayJsonSupport.escape(field)).append("\":").append(Double.toString(value));
    }

    private static void appendNumber(StringBuilder json, String field, long value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(ReplayJsonSupport.escape(field)).append("\":").append(value);
    }

    private static void appendNumber(StringBuilder json, String field, int value, boolean first) {
        appendNumber(json, field, (long) value, first);
    }

    private static void appendNumber(StringBuilder json, String field, double value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(ReplayJsonSupport.escape(field)).append("\":").append(Double.toString(value));
    }
}
