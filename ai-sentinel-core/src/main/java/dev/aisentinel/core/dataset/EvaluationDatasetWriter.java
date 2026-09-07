package dev.aisentinel.core.dataset;

import dev.aisentinel.core.contract.ContractRiskFactor;
import dev.aisentinel.core.contract.EvaluationEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic JSONL dataset writer for privacy-safe {@link EvaluationEvent} records.
 * <p>
 * The append order of records is the authoritative replay order. A completed dataset is
 * recognized only when both {@code events.jsonl} and {@code manifest.json} are present.
 */
public final class EvaluationDatasetWriter implements AutoCloseable {

    private final Path outputDirectory;
    private final Path eventsTempFile;
    private final Path manifestTempFile;
    private final BufferedWriter eventsWriter;
    private final String datasetId;
    private final String aiSentinelVersion;
    private final String featureSchemaVersion;
    private final String evaluationEventSchemaVersion;
    private final String sourceClassification;
    private final String transformationVersion;
    private final String description;
    private final String scenario;
    private final Instant createdAt;

    private long recordCount;
    private boolean closed;

    public EvaluationDatasetWriter(Path outputDirectory,
                                   String datasetId,
                                   String aiSentinelVersion,
                                   String featureSchemaVersion,
                                   String evaluationEventSchemaVersion,
                                   String sourceClassification,
                                   String transformationVersion,
                                   String description,
                                   String scenario) throws IOException {
        this(
            outputDirectory,
            datasetId,
            aiSentinelVersion,
            featureSchemaVersion,
            evaluationEventSchemaVersion,
            sourceClassification,
            transformationVersion,
            description,
            scenario,
            Instant.now()
        );
    }

    public EvaluationDatasetWriter(Path outputDirectory,
                                   String datasetId,
                                   String aiSentinelVersion,
                                   String featureSchemaVersion,
                                   String evaluationEventSchemaVersion,
                                   String sourceClassification,
                                   String transformationVersion,
                                   String description,
                                   String scenario,
                                   Instant createdAt) throws IOException {
        this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
        this.datasetId = requireNotBlank("datasetId", datasetId);
        this.aiSentinelVersion = requireNotBlank("aiSentinelVersion", aiSentinelVersion);
        this.featureSchemaVersion = requireNotBlank("featureSchemaVersion", featureSchemaVersion);
        this.evaluationEventSchemaVersion = requireNotBlank("evaluationEventSchemaVersion", evaluationEventSchemaVersion);
        this.sourceClassification = requireNotBlank("sourceClassification", sourceClassification);
        this.transformationVersion = requireNotBlank("transformationVersion", transformationVersion);
        this.description = description == null ? "" : description;
        this.scenario = scenario == null ? "" : scenario;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");

        Files.createDirectories(outputDirectory);
        Path eventsFile = outputDirectory.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME);
        Path manifestFile = outputDirectory.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME);
        if (Files.exists(eventsFile) || Files.exists(manifestFile)) {
            throw new FileAlreadyExistsException(outputDirectory.toString(),
                null,
                "dataset output already contains finalized artifacts");
        }
        this.eventsTempFile = outputDirectory.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME + ".tmp");
        this.manifestTempFile = outputDirectory.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME + ".tmp");
        Files.deleteIfExists(eventsTempFile);
        Files.deleteIfExists(manifestTempFile);
        this.eventsWriter = Files.newBufferedWriter(
            eventsTempFile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
    }

    public void append(EvaluationEvent event) {
        Objects.requireNonNull(event, "event");
        if (closed) {
            throw new IllegalStateException("writer already closed");
        }
        if (!featureSchemaVersion.equals(event.featureSchemaVersion())) {
            throw new IllegalArgumentException("event feature schema version mismatch");
        }
        if (!evaluationEventSchemaVersion.equals(event.eventSchemaVersion())) {
            throw new IllegalArgumentException("event schema version mismatch");
        }
        try {
            eventsWriter.write(EvaluationDatasetJson.writeEvent(event));
            eventsWriter.write('\n');
            recordCount++;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append evaluation event", e);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            eventsWriter.close();
            String eventsSha256 = EvaluationDatasetJson.sha256Hex(Files.readAllBytes(eventsTempFile));
            EvaluationDatasetManifest manifest = new EvaluationDatasetManifest(
                EvaluationDatasetSchemas.DATASET_SCHEMA_VERSION,
                datasetId,
                createdAt,
                aiSentinelVersion,
                featureSchemaVersion,
                evaluationEventSchemaVersion,
                recordCount,
                EvaluationDatasetSchemas.ORDERING_APPEND_ORDER,
                sourceClassification,
                transformationVersion,
                EvaluationDatasetSchemas.EVENTS_FILE_NAME,
                eventsSha256,
                description,
                scenario
            );
            Files.writeString(
                manifestTempFile,
                EvaluationDatasetJson.writeManifest(manifest) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            Files.move(
                eventsTempFile,
                outputDirectory.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
            Files.move(
                manifestTempFile,
                outputDirectory.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(manifestTempFile);
            Files.deleteIfExists(eventsTempFile);
            throw e;
        }
    }

    static String riskFactorSourceOrEmpty(ContractRiskFactor factor) {
        return factor.source() == null ? "" : factor.source();
    }

    static String requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }
}

final class EvaluationDatasetJson {

    private EvaluationDatasetJson() {
    }

    static String writeEvent(EvaluationEvent event) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        appendString(json, "eventSchemaVersion", event.eventSchemaVersion(), true);
        appendString(json, "eventId", event.eventId(), false);
        appendString(json, "observedAt", event.observedAt().toString(), false);
        appendOptionalString(json, "correlationId", event.correlationId());
        appendString(json, "identityKey", event.identityKey(), false);
        appendOptionalString(json, "identityType", event.identityType());
        appendString(json, "endpointKey", event.endpointKey(), false);
        appendString(json, "featureSchemaVersion", event.featureSchemaVersion(), false);
        json.append(",\"features\":{");
        appendNumber(json, "requestsPerWindow", event.features().requestsPerWindow(), true);
        appendNumber(json, "endpointEntropy", event.features().endpointEntropy(), false);
        appendNumber(json, "endpointConcentration", event.features().endpointConcentration(), false);
        appendNumber(json, "tokenAgeSeconds", event.features().tokenAgeSeconds(), false);
        appendNumber(json, "parameterCount", event.features().parameterCount(), false);
        appendNumber(json, "payloadSizeBytes", event.features().payloadSizeBytes(), false);
        appendNumber(json, "headerFingerprintHash", event.features().headerFingerprintHash(), false);
        appendNumber(json, "ipBucket", event.features().ipBucket(), false);
        json.append('}');
        appendString(json, "scorerId", event.scorerId(), false);
        appendOptionalString(json, "scorerVersion", event.scorerVersion());
        appendOptionalNumber(json, "anomalyScore", event.anomalyScore());
        appendOptionalNumber(json, "policyScore", event.policyScore());
        appendString(json, "action", event.action().name(), false);
        appendStringArray(json, "evaluationStatuses", event.evaluationStatuses().stream().map(Enum::name).toList(), false);
        json.append(",\"riskFactors\":[");
        appendRiskFactors(json, event.riskFactors());
        json.append(']');
        appendOptionalString(json, "policyId", event.policyId());
        appendOptionalString(json, "policyVersion", event.policyVersion());
        appendOptionalString(json, "evaluationMode", event.evaluationMode());
        json.append('}');
        return json.toString();
    }

    static String writeManifest(EvaluationDatasetManifest manifest) {
        StringBuilder json = new StringBuilder(384);
        json.append('{');
        appendString(json, "datasetSchemaVersion", manifest.datasetSchemaVersion(), true);
        appendString(json, "datasetId", manifest.datasetId(), false);
        appendString(json, "createdAt", manifest.createdAt().toString(), false);
        appendString(json, "aiSentinelVersion", manifest.aiSentinelVersion(), false);
        appendString(json, "featureSchemaVersion", manifest.featureSchemaVersion(), false);
        appendString(json, "evaluationEventSchemaVersion", manifest.evaluationEventSchemaVersion(), false);
        appendNumber(json, "recordCount", manifest.recordCount(), false);
        appendString(json, "ordering", manifest.ordering(), false);
        appendString(json, "sourceClassification", manifest.sourceClassification(), false);
        appendString(json, "transformationVersion", manifest.transformationVersion(), false);
        appendString(json, "eventsFile", manifest.eventsFile(), false);
        appendString(json, "eventsSha256", manifest.eventsSha256(), false);
        appendOptionalString(json, "description", manifest.description());
        appendOptionalString(json, "scenario", manifest.scenario());
        json.append('}');
        return json.toString();
    }

    static String sha256Hex(byte[] bytes) {
        return dev.aisentinel.distributed.training.TrainingFingerprintHashes.sha256HexBytes(bytes);
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
            appendOptionalString(json, "source", EvaluationDatasetWriter.riskFactorSourceOrEmpty(factor));
            json.append('}');
        }
    }

    private static void appendStringArray(StringBuilder json, String field, List<String> values, boolean first) {
        if (!first) {
            json.append(',');
        }
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
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendOptionalNumber(StringBuilder json, String field, Double value) {
        if (value == null) {
            return;
        }
        json.append(",\"").append(escape(field)).append("\":").append(Double.toString(value));
    }

    private static void appendNumber(StringBuilder json, String field, long value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":").append(value);
    }

    private static void appendNumber(StringBuilder json, String field, int value, boolean first) {
        appendNumber(json, field, (long) value, first);
    }

    private static void appendNumber(StringBuilder json, String field, double value, boolean first) {
        if (!first) {
            json.append(',');
        }
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
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
