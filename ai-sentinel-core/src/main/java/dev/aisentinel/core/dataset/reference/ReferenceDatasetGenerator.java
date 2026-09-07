package dev.aisentinel.core.dataset.reference;

import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.contract.ContractRiskFactor;
import dev.aisentinel.core.contract.EvaluationEvent;
import dev.aisentinel.core.contract.EvaluationEventSchemas;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.RiskExplanation;
import dev.aisentinel.core.decision.RiskFactor;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.FeatureSnapshot;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.store.BaselineStore;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.dataset.EvaluationDatasetSchemas;
import dev.aisentinel.core.dataset.EvaluationDatasetWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic generator for the repository's reference synthetic evaluation dataset.
 */
public final class ReferenceDatasetGenerator {

    public static final String DATASET_ID = "reference-synthetic-evaluation-v1";
    public static final Instant DATASET_CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    public static final Path TRACKED_DATASET_DIRECTORY = Path.of("evaluation/reference");
    public static final Path TRACKED_ANNOTATIONS_FILE = TRACKED_DATASET_DIRECTORY.resolve("annotations.json");
    public static final String ANNOTATIONS_FILE_NAME = "annotations.json";
    public static final String AI_SENTINEL_VERSION = "0.3.0";
    public static final String SOURCE_CLASSIFICATION = "synthetic-reference-evaluation";
    public static final String GENERATOR_VERSION = "reference-dataset-v1";
    public static final String SCORER_ID = "statistical";

    private static final Instant EVENT_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final long SECOND = 1_000L;

    public GeneratedReferenceDataset generate(Path outputDirectory) throws IOException {
        DatasetBuilder builder = new DatasetBuilder(outputDirectory);
        builder.scenario(
            "normal-established-baseline",
            ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
            ReferenceDatasetExpectedClass.NORMAL,
            false,
            false,
            List.of("requestsPerWindow", "payloadSizeBytes", "headerFingerprintHash", "ipBucket"),
            "Stable repeated behavior after baseline formation for one user identity.",
            scenario -> {
                for (int i = 0; i < 12; i++) {
                    scenario.addRequest("id:synthetic-001", request("/api/account/summary", "198.51.100.10", 128L, 0, null), 30 * SECOND);
                }
            }
        );
        builder.scenario(
            "warmup-new-identity",
            ReferenceDatasetScenarioCategory.WARMUP_NEW_IDENTITY,
            ReferenceDatasetExpectedClass.NORMAL,
            false,
            false,
            List.of("requestsPerWindow"),
            "Cold-start identity exercising warmup semantics without asserting maliciousness.",
            scenario -> {
                scenario.addRequest("id:synthetic-002", request("/api/profile", "198.51.100.11", 96L, 0, null), 20 * SECOND);
                scenario.addRequest("id:synthetic-002", request("/api/profile", "198.51.100.11", 96L, 0, null), 20 * SECOND);
            }
        );
        builder.scenario(
            "rapid-request-burst",
            ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST,
            ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
            true,
            false,
            List.of("requestsPerWindow"),
            "Calm baseline followed by a compressed burst on the same endpoint.",
            scenario -> {
                for (int i = 0; i < 8; i++) {
                    scenario.addBaselineRequest("id:synthetic-001", request("/api/account/summary", "198.51.100.10", 128L, 0, null), 25 * SECOND);
                }
                for (int i = 0; i < 10; i++) {
                    scenario.addEvaluationRequest("id:synthetic-001", request("/api/account/summary", "198.51.100.10", 128L, 0, null), SECOND);
                }
            }
        );
        builder.scenario(
            "endpoint-behavior-change",
            ReferenceDatasetScenarioCategory.ENDPOINT_BEHAVIOR_CHANGE,
            ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
            true,
            false,
            List.of("endpointEntropy", "endpointConcentration", "requestsPerWindow"),
            "Identity transitions from a stable endpoint to a rotating endpoint mix.",
            scenario -> {
                for (int i = 0; i < 6; i++) {
                    scenario.addBaselineRequest("id:synthetic-003", request("/api/catalog/view", "198.51.100.12", 180L, 1, null), 25 * SECOND);
                }
                String[] rotated = {
                    "/api/catalog/view",
                    "/api/orders/1001",
                    "/api/orders/1002",
                    "/api/support/ticket/2001",
                    "/api/profile/update",
                    "/api/orders/1003",
                    "/api/support/ticket/2002",
                    "/api/orders/1004"
                };
                for (String endpoint : rotated) {
                    scenario.addEvaluationRequest("id:synthetic-003", request(endpoint, "198.51.100.12", 180L, 1, null), 10 * SECOND);
                }
            }
        );
        builder.scenario(
            "payload-size-deviation",
            ReferenceDatasetScenarioCategory.PAYLOAD_SIZE_DEVIATION,
            ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
            true,
            false,
            List.of("payloadSizeBytes"),
            "Stable payload size followed by much larger requests on the same path.",
            scenario -> {
                for (int i = 0; i < 6; i++) {
                    scenario.addBaselineRequest("id:synthetic-004", request("/api/upload/chunk", "198.51.100.13", 256L, 0, null), 20 * SECOND);
                }
                for (int i = 0; i < 6; i++) {
                    scenario.addEvaluationRequest("id:synthetic-004", request("/api/upload/chunk", "198.51.100.13", 8_192L, 0, null), 8 * SECOND);
                }
            }
        );
        builder.scenario(
            "parameter-count-deviation",
            ReferenceDatasetScenarioCategory.PARAMETER_COUNT_DEVIATION,
            ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
            true,
            false,
            List.of("parameterCount"),
            "Baseline query/form shape expands sharply without exporting parameter values.",
            scenario -> {
                for (int i = 0; i < 6; i++) {
                    scenario.addBaselineRequest("id:synthetic-005", request("/api/report/filter", "198.51.100.14", 0L, 1, null), 20 * SECOND);
                }
                for (int i = 0; i < 6; i++) {
                    scenario.addEvaluationRequest("id:synthetic-005", request("/api/report/filter", "198.51.100.14", 0L, 8, null), 8 * SECOND);
                }
            }
        );
        builder.scenario(
            "token-age-change",
            ReferenceDatasetScenarioCategory.TOKEN_AGE_CHANGE,
            ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
            true,
            false,
            List.of("tokenAgeSeconds"),
            "Token-age behavior shifts from freshly issued to long-lived without embedding real tokens.",
            scenario -> {
                for (int i = 0; i < 6; i++) {
                    scenario.addBaselineRequest("id:synthetic-006", request("/api/session/refresh", "198.51.100.15", 64L, 0, 60L), 20 * SECOND);
                }
                for (int i = 0; i < 6; i++) {
                    scenario.addEvaluationRequest("id:synthetic-006", request("/api/session/refresh", "198.51.100.15", 64L, 0, 7_200L), 12 * SECOND);
                }
            }
        );
        builder.scenario(
            "low-variance-baseline-deviation",
            ReferenceDatasetScenarioCategory.LOW_VARIANCE_BASELINE_DEVIATION,
            ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
            true,
            false,
            List.of("payloadSizeBytes", "parameterCount"),
            "Low-variance calm behavior followed by bounded deviations that should not saturate to maximum risk.",
            scenario -> {
                for (int i = 0; i < 10; i++) {
                    scenario.addBaselineRequest("id:synthetic-007", request("/api/checkout", "198.51.100.16", 0L, 0, null), 15 * SECOND);
                }
                scenario.addEvaluationRequest("id:synthetic-007", request("/api/checkout", "198.51.100.16", 1L, 0, null), 10 * SECOND);
                scenario.addEvaluationRequest("id:synthetic-007", request("/api/checkout", "198.51.100.16", 1_024L, 1, null), 10 * SECOND);
                scenario.addEvaluationRequest("id:synthetic-007", request("/api/checkout", "198.51.100.16", 2_048L, 1, null), 10 * SECOND);
                scenario.addEvaluationRequest("id:synthetic-007", request("/api/checkout", "198.51.100.16", 4_096L, 1, null), 10 * SECOND);
            }
        );
        builder.scenario(
            "gradual-behavior-change",
            ReferenceDatasetScenarioCategory.GRADUAL_BEHAVIOR_CHANGE,
            ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
            true,
            false,
            List.of("payloadSizeBytes", "parameterCount"),
            "Gradual drift ramps payload and parameter complexity instead of introducing a single abrupt step.",
            scenario -> {
                long[] payloads = {128L, 160L, 192L, 224L, 256L, 320L, 512L, 768L, 1_024L, 1_280L, 1_536L, 2_048L};
                int[] params = {1, 1, 1, 1, 1, 2, 2, 2, 3, 3, 4, 4};
                for (int i = 0; i < payloads.length; i++) {
                    if (i < 4) {
                        scenario.addBaselineRequest("id:synthetic-008", request("/api/search", "198.51.100.17", payloads[i], params[i], null), 20 * SECOND);
                    } else {
                        scenario.addEvaluationRequest("id:synthetic-008", request("/api/search", "198.51.100.17", payloads[i], params[i], null), 12 * SECOND);
                    }
                }
            }
        );
        builder.scenario(
            "legitimate-bulk-operation",
            ReferenceDatasetScenarioCategory.LEGITIMATE_BULK_OPERATION,
            ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS,
            true,
            false,
            List.of("requestsPerWindow", "payloadSizeBytes"),
            "A benign but abrupt bulk export operation that may be anomalous without being malicious.",
            scenario -> {
                for (int i = 0; i < 6; i++) {
                    scenario.addBaselineRequest("id:synthetic-009", request("/api/export/status", "198.51.100.18", 96L, 0, null), 20 * SECOND);
                }
                for (int i = 0; i < 10; i++) {
                    scenario.addEvaluationRequest("id:synthetic-009", request("/api/export/run", "198.51.100.18", 16_384L, 2, null), 2 * SECOND);
                }
            }
        );
        builder.scenario(
            "interleaved-normal-identities",
            ReferenceDatasetScenarioCategory.INTERLEAVED_NORMAL_IDENTITIES,
            ReferenceDatasetExpectedClass.NORMAL,
            false,
            false,
            List.of("requestsPerWindow", "identity isolation"),
            "Two benign identities alternate requests to prove sequence interleaving without baseline leakage.",
            scenario -> {
                for (int i = 0; i < 6; i++) {
                    scenario.addRequest("id:synthetic-010", request("/api/inventory", "198.51.100.19", 128L, 0, null), 5 * SECOND);
                    scenario.addRequest("id:synthetic-011", request("/api/inventory", "198.51.100.20", 128L, 0, null), 5 * SECOND);
                }
            }
        );
        return builder.write();
    }

    public GeneratedReferenceDataset generateTrackedDataset() throws IOException {
        return generate(TRACKED_DATASET_DIRECTORY);
    }

    private static SyntheticRequestSpec request(String path, String remote, long contentLength, int parameterCount, Long tokenAgeSeconds) {
        return new SyntheticRequestSpec(path, remote, contentLength, parameterCount, tokenAgeSeconds);
    }

    public record GeneratedReferenceDataset(
        List<EvaluationEvent> events,
        ReferenceDatasetAnnotations annotations,
        Path outputDirectory,
        String eventsSha256,
        long eventsBytes,
        long annotationsBytes,
        long manifestBytes
    ) {
        public int scenarioCount() {
            return annotations.scenarios().size();
        }

        public int identityCount() {
            Set<String> identities = new LinkedHashSet<>();
            for (EvaluationEvent event : events) {
                identities.add(event.identityKey());
            }
            return identities.size();
        }

        public long totalBytes() {
            return eventsBytes + annotationsBytes + manifestBytes;
        }
    }

    private static final class DatasetBuilder {

        private final Path outputDirectory;
        private final AtomicLong clockMillis = new AtomicLong(EVENT_START.toEpochMilli());
        private final AtomicInteger eventCounter = new AtomicInteger();
        private final List<EvaluationEvent> events = new ArrayList<>();
        private final List<ReferenceDatasetScenarioAnnotation> annotations = new ArrayList<>();
        private final BaselineStore baselineStore = new BaselineStore(Duration.ofMinutes(5), 100_000, clockMillis::get);
        private final DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(
            baselineStore,
            100_000,
            300_000L,
            clockMillis::get
        );
        private final StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        private final dev.aisentinel.core.decision.SentinelDecisionEngine engine = new dev.aisentinel.core.decision.SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQuarantined.INSTANCE,
            NoopTelemetry.INSTANCE,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor()
        );

        private DatasetBuilder(Path outputDirectory) {
            this.outputDirectory = outputDirectory;
        }

        private void scenario(String id,
                              ReferenceDatasetScenarioCategory category,
                              ReferenceDatasetExpectedClass expectedClass,
                              boolean anomalyExpected,
                              boolean maliciousnessAsserted,
                              List<String> exercisedFeatures,
                              String notes,
                              ScenarioSpec spec) {
            ScenarioRecorder recorder = new ScenarioRecorder(id);
            spec.define(recorder);
            annotations.add(recorder.toAnnotation(
                category,
                expectedClass,
                anomalyExpected,
                maliciousnessAsserted,
                exercisedFeatures,
                notes
            ));
        }

        private GeneratedReferenceDataset write() throws IOException {
            ReferenceDatasetAnnotations annotationsFile = new ReferenceDatasetAnnotations(
                ReferenceDatasetAnnotations.SCHEMA_VERSION,
                DATASET_ID,
                "Synthetic engineering evaluation annotations for the reference dataset.",
                annotations
            );
            Files.createDirectories(outputDirectory);
            Files.deleteIfExists(outputDirectory.resolve(ANNOTATIONS_FILE_NAME));
            try (EvaluationDatasetWriter writer = new EvaluationDatasetWriter(
                outputDirectory,
                DATASET_ID,
                AI_SENTINEL_VERSION,
                FeatureSchema.VERSION_ID,
                EvaluationEventSchemas.CURRENT_VERSION,
                SOURCE_CLASSIFICATION,
                GENERATOR_VERSION,
                "Deterministic synthetic engineering evaluation dataset for future replay and detection work.",
                "reference synthetic evaluation dataset",
                DATASET_CREATED_AT
            )) {
                for (EvaluationEvent event : events) {
                    writer.append(event);
                }
            }
            Files.writeString(
                outputDirectory.resolve(ANNOTATIONS_FILE_NAME),
                annotationsJson(annotationsFile) + "\n",
                StandardCharsets.UTF_8
            );
            return new GeneratedReferenceDataset(
                List.copyOf(events),
                annotationsFile,
                outputDirectory,
                dev.aisentinel.distributed.training.TrainingFingerprintHashes.sha256HexBytes(
                    Files.readAllBytes(outputDirectory.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME))
                ),
                Files.size(outputDirectory.resolve(EvaluationDatasetSchemas.EVENTS_FILE_NAME)),
                Files.size(outputDirectory.resolve(ANNOTATIONS_FILE_NAME)),
                Files.size(outputDirectory.resolve(EvaluationDatasetSchemas.MANIFEST_FILE_NAME))
            );
        }

        private final class ScenarioRecorder {
            private final String id;
            private final List<String> eventIds = new ArrayList<>();
            private final List<String> baselineEventIds = new ArrayList<>();
            private final List<String> evaluationEventIds = new ArrayList<>();
            private final Set<String> identityKeys = new LinkedHashSet<>();

            private ScenarioRecorder(String id) {
                this.id = id;
            }

            private void addRequest(String identityKey, SyntheticRequestSpec request, long advanceMillis) {
                add(identityKey, request, advanceMillis, false, false);
            }

            private void addBaselineRequest(String identityKey, SyntheticRequestSpec request, long advanceMillis) {
                add(identityKey, request, advanceMillis, true, false);
            }

            private void addEvaluationRequest(String identityKey, SyntheticRequestSpec request, long advanceMillis) {
                add(identityKey, request, advanceMillis, false, true);
            }

            private void add(String identityKey, SyntheticRequestSpec requestSpec, long advanceMillis, boolean baseline, boolean evaluation) {
                SyntheticHttpRequestView request = toRequest(requestSpec, clockMillis.get());
                RequestContext context = new RequestContext();
                RequestFeatures features = extractor.extract(request, identityKey, context);
                RiskDecision decision = engine.evaluate(request, identityKey, features, context);
                String eventId = "evt-ref-" + zeroPad(eventCounter.incrementAndGet());
                String correlationId = "corr-ref-" + zeroPad(eventCounter.get());
                EvaluationEvent event = new EvaluationEvent(
                    EvaluationEventSchemas.CURRENT_VERSION,
                    eventId,
                    Instant.ofEpochMilli(clockMillis.get()),
                    correlationId,
                    identityKey,
                    "SYNTHETIC_PSEUDONYM",
                    "route:" + features.endpoint(),
                    FeatureSchema.VERSION_ID,
                    FeatureSnapshot.from(features),
                    SCORER_ID,
                    AI_SENTINEL_VERSION,
                    finiteOrNull(decision.anomalyScore()),
                    finiteOrNull(decision.policyScore()),
                    decision.action(),
                    decision.evaluationStatuses().stream().sorted().toList(),
                    toContractFactors(decision.explanation()),
                    "threshold-policy-default",
                    AI_SENTINEL_VERSION,
                    "MONITOR"
                );
                events.add(event);
                eventIds.add(eventId);
                if (baseline) {
                    baselineEventIds.add(eventId);
                }
                if (evaluation) {
                    evaluationEventIds.add(eventId);
                }
                identityKeys.add(identityKey);
                clockMillis.addAndGet(advanceMillis);
            }

            private ReferenceDatasetScenarioAnnotation toAnnotation(ReferenceDatasetScenarioCategory category,
                                                                   ReferenceDatasetExpectedClass expectedClass,
                                                                   boolean anomalyExpected,
                                                                   boolean maliciousnessAsserted,
                                                                   List<String> exercisedFeatures,
                                                                   String notes) {
                return new ReferenceDatasetScenarioAnnotation(
                    id,
                    category,
                    expectedClass,
                    anomalyExpected,
                    maliciousnessAsserted,
                    eventIds,
                    baselineEventIds,
                    evaluationEventIds,
                    List.copyOf(identityKeys),
                    exercisedFeatures,
                    notes
                );
            }
        }
    }

    @FunctionalInterface
    private interface ScenarioSpec {
        void define(DatasetBuilder.ScenarioRecorder recorder);
    }

    private static List<ContractRiskFactor> toContractFactors(RiskExplanation explanation) {
        List<ContractRiskFactor> factors = new ArrayList<>();
        for (RiskFactor factor : explanation.factors()) {
            factors.add(new ContractRiskFactor(
                factor.code().name(),
                factor.category().name(),
                factor.severity().name(),
                factor.contribution(),
                factor.confidence(),
                factor.evidenceRef(),
                factor.explanation(),
                factor.source()
            ));
        }
        return List.copyOf(factors);
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static SyntheticHttpRequestView toRequest(SyntheticRequestSpec spec, long nowMillis) {
        SyntheticHttpRequestView request = new SyntheticHttpRequestView()
            .requestUri(spec.path())
            .method("GET")
            .remoteAddr(spec.remote())
            .header("Accept", "application/json")
            .header("User-Agent", "ai-sentinel-reference-generator");
        if (spec.contentLength() > 0L) {
            request.header("Content-Length", Long.toString(spec.contentLength()));
        }
        for (int i = 1; i <= spec.parameterCount(); i++) {
            request.parameter("p" + i, "v" + i);
        }
        if (spec.tokenAgeSeconds() != null) {
            long issuedAtEpochSeconds = (nowMillis / 1000L) - spec.tokenAgeSeconds();
            request.header("Authorization", "Bearer synthetic");
            request.header("X-Token-Issued-At", Long.toString(issuedAtEpochSeconds));
        }
        return request;
    }

    static String annotationsJson(ReferenceDatasetAnnotations annotations) {
        StringBuilder json = new StringBuilder(4096);
        json.append('{');
        appendString(json, "schemaVersion", annotations.schemaVersion(), true);
        appendString(json, "datasetId", annotations.datasetId(), false);
        appendOptionalString(json, "description", annotations.description());
        json.append(",\"scenarios\":[");
        for (int i = 0; i < annotations.scenarios().size(); i++) {
            ReferenceDatasetScenarioAnnotation scenario = annotations.scenarios().get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append('{');
            appendString(json, "id", scenario.id(), true);
            appendString(json, "category", scenario.category().name(), false);
            appendString(json, "expectedClass", scenario.expectedClass().name(), false);
            appendBoolean(json, "anomalyExpected", scenario.anomalyExpected(), false);
            appendBoolean(json, "maliciousnessAsserted", scenario.maliciousnessAsserted(), false);
            appendStringArray(json, "eventIds", scenario.eventIds(), false);
            appendStringArray(json, "baselineEventIds", scenario.baselineEventIds(), false);
            appendStringArray(json, "evaluationEventIds", scenario.evaluationEventIds(), false);
            appendStringArray(json, "identityKeys", scenario.identityKeys(), false);
            appendStringArray(json, "exercisedFeatures", scenario.exercisedFeatures(), false);
            appendOptionalString(json, "notes", scenario.notes());
            json.append('}');
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendBoolean(StringBuilder json, String field, boolean value, boolean first) {
        if (!first) {
            json.append(',');
        }
        json.append('"').append(escape(field)).append("\":").append(value);
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
                        out.append(unicodeEscape(c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String zeroPad(int value) {
        if (value < 10) {
            return "000" + value;
        }
        if (value < 100) {
            return "00" + value;
        }
        if (value < 1000) {
            return "0" + value;
        }
        return Integer.toString(value);
    }

    private static String unicodeEscape(char c) {
        String hex = Integer.toHexString(c);
        StringBuilder out = new StringBuilder(6);
        out.append("\\u");
        for (int i = hex.length(); i < 4; i++) {
            out.append('0');
        }
        out.append(hex);
        return out.toString();
    }

    private enum NoopTelemetry implements TelemetryEmitter {
        INSTANCE;

        @Override
        public void emit(dev.aisentinel.core.telemetry.TelemetryEvent event) {
        }
    }

    private enum NeverQuarantined implements EnforcementHandler {
        INSTANCE;

        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("reference dataset generation must not apply enforcement");
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }

    private record SyntheticRequestSpec(
        String path,
        String remote,
        long contentLength,
        int parameterCount,
        Long tokenAgeSeconds
    ) {
    }

    private static final class SyntheticHttpRequestView implements HttpRequestView {
        private String requestUri = "/";
        private String method = "GET";
        private String remoteAddr = "127.0.0.1";
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final Map<String, String[]> parameters = new LinkedHashMap<>();

        private SyntheticHttpRequestView requestUri(String value) {
            this.requestUri = value;
            return this;
        }

        private SyntheticHttpRequestView method(String value) {
            this.method = value;
            return this;
        }

        private SyntheticHttpRequestView remoteAddr(String value) {
            this.remoteAddr = value;
            return this;
        }

        private SyntheticHttpRequestView header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        private SyntheticHttpRequestView parameter(String name, String... values) {
            parameters.put(name, values);
            return this;
        }

        @Override
        public String getRequestURI() {
            return requestUri;
        }

        @Override
        public String getMethod() {
            return method;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return Collections.unmodifiableMap(parameters);
        }

        @Override
        public String getHeader(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(headers.keySet());
        }

        @Override
        public String getRemoteAddr() {
            return remoteAddr;
        }

        @Override
        public String getSessionId() {
            return null;
        }

        @Override
        public boolean hasSession() {
            return false;
        }

        @Override
        public boolean isNewSession() {
            return false;
        }

        @Override
        public long getSessionCreationTimeMillis() {
            return 0L;
        }

        @Override
        public long getSessionLastAccessedTimeMillis() {
            return 0L;
        }
    }
}
