package dev.aisentinel.core.pilot;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Local-file MONITOR pilot evidence session.
 * Writes observations.jsonl during collection and finalizes summary + manifest on close.
 */
public final class LocalPilotEvidenceSession implements PilotObservationRecorder, AutoCloseable {

    public static final String OBSERVATIONS_FILE = "observations.jsonl";
    public static final String SUMMARY_FILE = "pilot-summary.json";
    public static final String MANIFEST_FILE = "pilot-manifest.json";
    public static final String LOCK_FILE = ".pilot-session.lock";

    private static final Logger log = LoggerFactory.getLogger(LocalPilotEvidenceSession.class);
    private static final Pattern PILOT_SESSION_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    private final Path sessionDirectory;
    private final String pilotSessionId;
    private final PilotConfigSnapshot config;
    private final String configDigest;
    private final PilotPseudonymizer pseudonymizer;
    private final Instant startedAt;
    private final Object writeLock = new Object();
    private final PilotSummaryAccumulator accumulator;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicBoolean finalized = new AtomicBoolean(false);
    private final MessageDigest observationsDigest;
    private BufferedWriter observationsWriter;
    private long observationCount;
    private Path lockPath;

    public LocalPilotEvidenceSession(
        Path sessionDirectory,
        String pilotSessionId,
        PilotConfigSnapshot config,
        PilotPseudonymizer pseudonymizer
    ) throws IOException {
        this.sessionDirectory = Objects.requireNonNull(sessionDirectory, "sessionDirectory").toAbsolutePath().normalize();
        this.pilotSessionId = validatePilotSessionId(pilotSessionId);
        this.config = Objects.requireNonNull(config, "config");
        if (!PilotObservation.RUNTIME_MODE_MONITOR.equals(config.runtimeMode())) {
            throw new IllegalArgumentException("pilot evidence session requires runtimeMode=MONITOR");
        }
        this.configDigest = PilotConfigDigest.sha256Hex(config);
        this.pseudonymizer = Objects.requireNonNull(pseudonymizer, "pseudonymizer");
        this.startedAt = Instant.now();
        this.accumulator = new PilotSummaryAccumulator(pilotSessionId);
        try {
            this.observationsDigest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 required", e);
        }
        prepareDirectory();
        this.observationsWriter = Files.newBufferedWriter(
            this.sessionDirectory.resolve(OBSERVATIONS_FILE),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        );
    }

    private void prepareDirectory() throws IOException {
        if (Files.exists(sessionDirectory, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(sessionDirectory)) {
            throw new IOException("pilot output directory must not be a symbolic link");
        }
        if (Files.exists(sessionDirectory)) {
            if (!Files.isDirectory(sessionDirectory)) {
                throw new IOException("pilot output path exists and is not a directory: " + sessionDirectory);
            }
            try (var stream = Files.list(sessionDirectory)) {
                if (stream.findAny().isPresent()) {
                    throw new IOException(
                        "pilot output directory must be empty (refusing overwrite): " + sessionDirectory);
                }
            }
        } else {
            Files.createDirectories(sessionDirectory);
        }
        lockPath = sessionDirectory.resolve(LOCK_FILE);
        Files.writeString(lockPath, pilotSessionId, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static String validatePilotSessionId(String pilotSessionId) {
        String value = Objects.requireNonNull(pilotSessionId, "pilotSessionId").trim();
        if (!PILOT_SESSION_ID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "pilotSessionId must contain only ASCII letters, digits, '.', '_' or '-' and be 1-80 characters");
        }
        return value;
    }

    @Override
    public void record(
        String pipelineIdentityHash,
        RiskDecision decision,
        long pipelineLatencyNanos,
        boolean requestProceeded
    ) {
        if (!open.get() || decision == null || decision.features() == null) {
            return;
        }
        if (!PilotObservation.RUNTIME_MODE_MONITOR.equals(config.runtimeMode())) {
            return;
        }
        try {
            RequestFeatures features = decision.features();
            List<String> statuses = decision.evaluationStatuses().stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.toCollection(ArrayList::new));
            String baselineStatus = deriveBaselineUpdateStatus(decision);
            Double policyScore = Double.compare(decision.anomalyScore(), decision.policyScore()) == 0
                ? null
                : decision.policyScore();
            PilotObservation observation = new PilotObservation(
                PilotObservation.SCHEMA_VERSION,
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                pilotSessionId,
                PilotObservation.EVIDENCE_CLASS,
                pseudonymizer.pseudonymize(pipelineIdentityHash),
                pseudonymizer.pseudonymizeEndpoint(features.endpoint()),
                PilotObservationFeatures.from(features),
                decision.anomalyScore(),
                policyScore,
                statuses,
                decision.action().name(),
                false,
                PilotObservation.REQUEST_OUTCOME_CONTINUED,
                PilotObservation.RUNTIME_MODE_MONITOR,
                baselineStatus,
                config.scorerId(),
                config.scorerVersion(),
                config.softwareVersion(),
                FeatureSchema.VERSION_ID,
                pipelineLatencyNanos >= 0 ? pipelineLatencyNanos : null,
                ""
            );
            String line = PilotObservationJson.write(observation);
            synchronized (writeLock) {
                if (!open.get()) {
                    return;
                }
                observationsWriter.write(line);
                observationsWriter.write('\n');
                observationsWriter.flush();
                byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                observationsDigest.update(bytes);
                observationCount++;
                accumulator.accept(observation);
            }
        } catch (Exception e) {
            log.warn("Pilot observation write failed (application request continues): {}: {}",
                e.getClass().getSimpleName(), e.getMessage());
        }
    }

    static String deriveBaselineUpdateStatus(RiskDecision decision) {
        if (decision.hasStatus(EvaluationStatus.INVALID_SCORE)) {
            return "UNAVAILABLE";
        }
        if (decision.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)) {
            return "SKIPPED";
        }
        return "ACCEPTED";
    }

    public Path sessionDirectory() {
        return sessionDirectory;
    }

    public String pilotSessionId() {
        return pilotSessionId;
    }

    public String configDigest() {
        return configDigest;
    }

    public boolean isOpen() {
        return open.get() && !finalized.get();
    }

    @Override
    public void close() throws IOException {
        finalizeSession();
    }

    public void finalizeSession() throws IOException {
        if (!finalized.compareAndSet(false, true)) {
            return;
        }
        open.set(false);
        Instant endedAt = Instant.now();
        String observationsSha;
        PilotSummary summary;
        synchronized (writeLock) {
            if (observationsWriter != null) {
                observationsWriter.flush();
                observationsWriter.close();
                observationsWriter = null;
            }
            observationsSha = TrainingFingerprintHashes.sha256HexBytes(observationsDigest.digest());
            // Recompute digest from file bytes to ensure manifest matches persisted content.
            byte[] fileBytes = Files.readAllBytes(sessionDirectory.resolve(OBSERVATIONS_FILE));
            observationsSha = TrainingFingerprintHashes.sha256HexBytes(fileBytes);
            observationCount = countLines(fileBytes);
            summary = rebuildSummaryFromFile(sessionDirectory.resolve(OBSERVATIONS_FILE));
        }
        String summaryJson = summary.toJson();
        Path summaryPath = sessionDirectory.resolve(SUMMARY_FILE);
        atomicWriteString(summaryPath, summaryJson);
        String summarySha = TrainingFingerprintHashes.sha256HexUtf8(summaryJson);

        PilotManifest manifest = new PilotManifest(
            PilotManifest.SCHEMA_VERSION,
            pilotSessionId,
            PilotObservation.EVIDENCE_CLASS,
            startedAt.toString(),
            endedAt.toString(),
            config.runtimeMode(),
            config.softwareVersion(),
            config.featureSchemaVersion(),
            config.observationSchemaVersion(),
            configDigest,
            config.scorerId(),
            config.scorerVersion(),
            config.baselineUpdatePolicy(),
            config.policyEngineId(),
            observationCount,
            observationsSha,
            summarySha,
            PilotManifest.CLAIM_BOUNDARY
        );
        atomicWriteString(sessionDirectory.resolve(MANIFEST_FILE), manifest.toJson());
        if (lockPath != null) {
            Files.deleteIfExists(lockPath);
        }
    }

    private PilotSummary rebuildSummaryFromFile(Path observationsPath) throws IOException {
        PilotSummaryAccumulator rebuild = new PilotSummaryAccumulator(pilotSessionId);
        // Lightweight parse for summary rebuild from persisted lines.
        for (String line : Files.readAllLines(observationsPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            rebuild.accept(parseObservationLine(line));
        }
        return rebuild.build();
    }

    /**
     * Minimal parser sufficient for summary rebuild and verifier tests.
     */
    static PilotObservation parseObservationLine(String line) {
        String schemaVersion = requireJsonString(line, "schemaVersion");
        String observationId = requireJsonString(line, "observationId");
        String observedAt = requireJsonString(line, "observedAt");
        String pilotSessionId = requireJsonString(line, "pilotSessionId");
        String evidenceClass = requireJsonString(line, "evidenceClass");
        String pseudonymousIdentity = requireJsonString(line, "pseudonymousIdentity");
        String endpointKey = requireJsonString(line, "endpointKey");
        double requestsPerWindow = requireJsonDouble(line, "requestsPerWindow");
        double endpointEntropy = requireJsonDouble(line, "endpointEntropy");
        double endpointConcentration = requireJsonDouble(line, "endpointConcentration");
        double tokenAgeSeconds = requireJsonDouble(line, "tokenAgeSeconds");
        int parameterCount = (int) requireJsonLong(line, "parameterCount");
        long payloadSizeBytes = requireJsonLong(line, "payloadSizeBytes");
        long headerFingerprintHash = requireJsonLong(line, "headerFingerprintHash");
        int ipBucket = (int) requireJsonLong(line, "ipBucket");
        double anomalyScore = requireJsonDouble(line, "anomalyScore");
        Double policyScore = optionalJsonDouble(line, "policyScore");
        List<String> statuses = parseStringArray(line, "evaluationStatuses");
        String riskDerivedAction = requireJsonString(line, "riskDerivedAction");
        boolean enforcementApplied = line.contains("\"enforcementApplied\":true");
        String requestOutcome = requireJsonString(line, "requestOutcome");
        String runtimeMode = requireJsonString(line, "runtimeMode");
        String baselineUpdateStatus = requireJsonString(line, "baselineUpdateStatus");
        String scorerId = optionalJsonString(line, "scorerId");
        String scorerVersion = optionalJsonString(line, "scorerVersion");
        String softwareVersion = requireJsonString(line, "softwareVersion");
        String featureSchemaVersion = requireJsonString(line, "featureSchemaVersion");
        Long latency = optionalJsonLong(line, "pipelineLatencyNanos");
        String failOpenReason = optionalJsonString(line, "failOpenReason");
        return new PilotObservation(
            schemaVersion,
            observationId,
            observedAt,
            pilotSessionId,
            evidenceClass,
            pseudonymousIdentity,
            endpointKey,
            new PilotObservationFeatures(
                requestsPerWindow, endpointEntropy, endpointConcentration, tokenAgeSeconds,
                parameterCount, payloadSizeBytes, headerFingerprintHash, ipBucket
            ),
            anomalyScore,
            policyScore,
            statuses,
            riskDerivedAction,
            enforcementApplied,
            requestOutcome,
            runtimeMode,
            baselineUpdateStatus,
            scorerId == null ? "" : scorerId,
            scorerVersion == null ? "" : scorerVersion,
            softwareVersion,
            featureSchemaVersion,
            latency,
            failOpenReason == null ? "" : failOpenReason
        );
    }

    private static long countLines(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        long count = 0;
        for (byte b : bytes) {
            if (b == '\n') {
                count++;
            }
        }
        if (bytes[bytes.length - 1] != '\n') {
            count++;
        }
        return count;
    }

    private static void atomicWriteString(Path path, String content) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String requireJsonString(String json, String field) {
        String value = optionalJsonString(json, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing string field: " + field);
        }
        return value;
    }

    private static String optionalJsonString(String json, String field) {
        String key = "\"" + field + "\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(++i));
                continue;
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return null;
    }

    private static double requireJsonDouble(String json, String field) {
        Double value = optionalJsonDouble(json, field);
        if (value == null) {
            throw new IllegalArgumentException("missing number field: " + field);
        }
        return value;
    }

    private static Double optionalJsonDouble(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                end++;
            } else {
                break;
            }
        }
        if (end == start) {
            return null;
        }
        return Double.parseDouble(json.substring(start, end));
    }

    private static long requireJsonLong(String json, String field) {
        Long value = optionalJsonLong(json, field);
        if (value == null) {
            throw new IllegalArgumentException("missing long field: " + field);
        }
        return value;
    }

    private static Long optionalJsonLong(String json, String field) {
        String key = "\"" + field + "\":";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if ((c >= '0' && c <= '9') || c == '-') {
                end++;
            } else {
                break;
            }
        }
        if (end == start) {
            return null;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static List<String> parseStringArray(String json, String field) {
        String key = "\"" + field + "\":[";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return List.of();
        }
        int start = idx + key.length();
        int end = json.indexOf(']', start);
        if (end < 0) {
            return List.of();
        }
        String body = json.substring(start, end).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            int q1 = body.indexOf('"', i);
            if (q1 < 0) {
                break;
            }
            int q2 = body.indexOf('"', q1 + 1);
            if (q2 < 0) {
                break;
            }
            out.add(body.substring(q1 + 1, q2));
            i = q2 + 1;
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }
}
