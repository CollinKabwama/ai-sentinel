package dev.aisentinel.core.replay;

import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.contract.ContractRiskFactor;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ordered deterministic replay over compatible evaluation datasets.
 */
public final class ReplayEngine {

    private final ReplayRuntimeFactory runtimeFactory;

    public ReplayEngine() {
        this(ReplayEngine::newRuntime);
    }

    ReplayEngine(ReplayRuntimeFactory runtimeFactory) {
        this.runtimeFactory = runtimeFactory;
    }

    public ReplayRun run(ReplayDataset dataset, ReplayConfiguration configuration, Path outputDirectory) throws IOException {
        ReplayDataset safeDataset = dataset == null ? null : dataset;
        ReplayConfiguration safeConfiguration = configuration == null ? null : configuration;
        if (safeDataset == null) {
            throw new ReplayException(ReplayFailureKind.CONFIGURATION_FAILURE, "dataset is required");
        }
        if (safeConfiguration == null) {
            throw new ReplayException(ReplayFailureKind.CONFIGURATION_FAILURE, "configuration is required");
        }
        ReplayRuntime runtime = runtimeFactory.create(safeConfiguration);
        String replayRunId = deterministicRunId(safeDataset, safeConfiguration);
        ReplayRunWriter.ReplayRunManifestBuilder manifestBuilder = new ReplayRunWriter.ReplayRunManifestBuilder(
            replayRunId,
            safeDataset.manifest().datasetId(),
            safeDataset.eventsSha256(),
            safeDataset.manifest().datasetSchemaVersion(),
            safeDataset.manifest().evaluationEventSchemaVersion(),
            safeDataset.manifest().featureSchemaVersion(),
            safeDataset.annotations().schemaVersion(),
            safeConfiguration.scorer().scorerId(),
            safeConfiguration.scorer().scorerVersion(),
            safeConfiguration.policy().policyId(),
            safeConfiguration.policy().policyVersion(),
            safeConfiguration.configurationFingerprint(),
            safeConfiguration.replayMode(),
            safeDataset.manifest().ordering(),
            safeConfiguration.aiSentinelVersion()
        );
        List<ReplayResult> emitted = new ArrayList<>(safeDataset.eventCount());
        try (ReplayRunWriter writer = new ReplayRunWriter(outputDirectory, manifestBuilder)) {
            for (ReplayDataset.ReplaySourceEvent sourceEvent : safeDataset.events()) {
                ReplayResult result = replayOne(sourceEvent.replayInput(), runtime, replayRunId, safeConfiguration);
                writer.append(result);
                emitted.add(result);
            }
        } catch (ReplayException e) {
            throw e;
        } catch (IOException e) {
            throw new ReplayException(ReplayFailureKind.OUTPUT_WRITE_FAILURE, "Failed to write replay output", e);
        }
        Path resultsPath = outputDirectory.resolve(ReplaySchemas.RESULTS_FILE_NAME);
        Path manifestPath = outputDirectory.resolve(ReplaySchemas.MANIFEST_FILE_NAME);
        String resultsSha256 = TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(resultsPath));
        ReplayRunManifest manifest = new ReplayRunManifest(
            ReplaySchemas.REPLAY_SCHEMA_VERSION,
            replayRunId,
            safeDataset.manifest().datasetId(),
            safeDataset.eventsSha256(),
            safeDataset.manifest().datasetSchemaVersion(),
            safeDataset.manifest().evaluationEventSchemaVersion(),
            safeDataset.manifest().featureSchemaVersion(),
            safeDataset.annotations().schemaVersion(),
            safeConfiguration.scorer().scorerId(),
            safeConfiguration.scorer().scorerVersion(),
            safeConfiguration.policy().policyId(),
            safeConfiguration.policy().policyVersion(),
            safeConfiguration.configurationFingerprint(),
            safeConfiguration.replayMode(),
            safeDataset.manifest().ordering(),
            emitted.size(),
            safeConfiguration.aiSentinelVersion(),
            ReplaySchemas.RESULTS_FILE_NAME,
            resultsSha256
        );
        return new ReplayRun(
            List.copyOf(emitted),
            manifest,
            outputDirectory,
            resultsSha256,
            Files.size(resultsPath),
            Files.size(manifestPath)
        );
    }

    private static ReplayRuntime newRuntime(ReplayConfiguration configuration) {
        AnomalyScorer scorer = newScorer(configuration.scorer());
        return new ReplayRuntime(scorer, newDecisionEngine(configuration, scorer));
    }

    static SentinelDecisionEngine newDecisionEngine(ReplayConfiguration configuration, AnomalyScorer scorer) {
        ThresholdPolicyEngine policy = new ThresholdPolicyEngine(
            configuration.policy().moderateThreshold(),
            configuration.policy().elevatedThreshold(),
            configuration.policy().highThreshold(),
            configuration.policy().criticalThreshold()
        );
        return new SentinelDecisionEngine(
            scorer,
            policy,
            ReplayNeverQuarantined.INSTANCE,
            ReplayNoopTelemetry.INSTANCE,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor()
        );
    }

    private static AnomalyScorer newScorer(ReplayScorerConfiguration configuration) {
        return switch (configuration.scorerKind()) {
            case STATISTICAL -> new StatisticalScorer(
                configuration.maxKeys(),
                configuration.ttlMs(),
                configuration.warmupMinSamples(),
                configuration.warmupScore()
            );
            case ISOLATION_FOREST, COMPOSITE -> throw new ReplayException(
                ReplayFailureKind.CONFIGURATION_FAILURE,
                "Requested scorer is not yet supported for deterministic replay without explicit model/config support: "
                    + configuration.scorerKind());
        };
    }

    private static ReplayResult replayOne(ReplayInputRecord input,
                                          ReplayRuntime runtime,
                                          String replayRunId,
                                          ReplayConfiguration configuration) {
        RequestFeatures features = toRequestFeatures(input);
        RequestContext ctx = new RequestContext();
        RiskDecision decision;
        try {
            decision = runtime.engine().evaluate(new ReplayHttpRequestView(input), input.identityKey(), features, ctx);
        } catch (ReplayException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ReplayException(ReplayFailureKind.POLICY_FAILURE, "Replay evaluation failed for " + input.eventId(), e);
        }
        if (decision == null) {
            throw new ReplayException(ReplayFailureKind.SCORER_FAILURE, "Replay scorer failed for " + input.eventId());
        }
        Double anomalyScore = asSerializableScore(decision.anomalyScore());
        Double policyScore = asSerializableScore(decision.policyScore());
        return new ReplayResult(
            ReplaySchemas.REPLAY_SCHEMA_VERSION,
            replayRunId,
            ReplayResultStatus.REPLAYED,
            input.sequenceNumber(),
            input.eventId(),
            input.correlationId(),
            input.identityKey(),
            input.identityType(),
            input.observedAt(),
            input.endpointKey(),
            input.featureSchemaVersion(),
            configuration.scorer().scorerId(),
            configuration.scorer().scorerVersion(),
            anomalyScore,
            policyScore,
            decision.action(),
            decision.evaluationStatuses().stream().sorted().toList(),
            List.copyOf(decision.explanation().factors().stream().map(ReplayEngine::toContractRiskFactor).toList()),
            configuration.policy().policyId(),
            configuration.policy().policyVersion(),
            configuration.policy().evaluationMode()
        );
    }

    private static RequestFeatures toRequestFeatures(ReplayInputRecord input) {
        return RequestFeatures.builder()
            .identityHash(input.identityKey())
            .endpoint(endpointFromKey(input.endpointKey()))
            .timestampMillis(input.observedAt().toEpochMilli())
            .requestsPerWindow(input.features().requestsPerWindow())
            .endpointEntropy(input.features().endpointEntropy())
            .endpointConcentration(input.features().endpointConcentration())
            .tokenAgeSeconds(input.features().tokenAgeSeconds())
            .parameterCount(input.features().parameterCount())
            .payloadSizeBytes(input.features().payloadSizeBytes())
            .headerFingerprintHash(input.features().headerFingerprintHash())
            .ipBucket(input.features().ipBucket())
            .build();
    }

    private static String endpointFromKey(String endpointKey) {
        return endpointKey.startsWith("route:") ? endpointKey.substring("route:".length()) : endpointKey;
    }

    private static ContractRiskFactor toContractRiskFactor(dev.aisentinel.core.decision.RiskFactor factor) {
        return new ContractRiskFactor(
            factor.code().name(),
            factor.category().name(),
            factor.severity().name(),
            factor.contribution(),
            factor.confidence(),
            factor.evidenceRef(),
            factor.explanation(),
            factor.source()
        );
    }

    private static Double asSerializableScore(double score) {
        return Double.isFinite(score) && score >= 0.0 ? Math.min(1.0, score) : null;
    }

    static String deterministicRunId(ReplayDataset dataset, ReplayConfiguration configuration) {
        String fingerprint = dataset.eventsSha256()
            + "|" + dataset.manifest().datasetId()
            + "|" + configuration.replayMode()
            + "|" + configuration.scorer().scorerId()
            + "|" + configuration.scorer().scorerVersion()
            + "|" + configuration.policy().policyId()
            + "|" + configuration.policy().policyVersion()
            + "|" + configuration.aiSentinelVersion()
            + "|" + configuration.configurationFingerprint();
        return "replay-" + TrainingFingerprintHashes.sha256HexUtf8(fingerprint).substring(0, 16);
    }

    static record ReplayRuntime(
        AnomalyScorer scorer,
        SentinelDecisionEngine engine
    ) {
    }

    @FunctionalInterface
    interface ReplayRuntimeFactory {
        ReplayRuntime create(ReplayConfiguration configuration);
    }

    public record ReplayRun(
        List<ReplayResult> results,
        ReplayRunManifest manifest,
        Path outputDirectory,
        String resultsSha256,
        long resultsBytes,
        long manifestBytes
    ) {
        public int resultCount() {
            return results.size();
        }
    }

    private static final class ReplayHttpRequestView implements HttpRequestView {
        private final String requestUri;

        private ReplayHttpRequestView(ReplayInputRecord input) {
            this.requestUri = endpointFromKey(input.endpointKey());
        }

        @Override
        public String getRequestURI() {
            return requestUri;
        }

        @Override
        public String getMethod() {
            return "GET";
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return Collections.emptyMap();
        }

        @Override
        public String getHeader(String name) {
            return null;
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public String getRemoteAddr() {
            return "";
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

    private enum ReplayNoopTelemetry implements dev.aisentinel.core.telemetry.TelemetryEmitter {
        INSTANCE;

        @Override
        public void emit(dev.aisentinel.core.telemetry.TelemetryEvent event) {
        }
    }

    private enum ReplayNeverQuarantined implements EnforcementHandler {
        INSTANCE;

        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("replay engine must not apply enforcement");
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }
}
