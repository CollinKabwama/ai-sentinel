package dev.aisentinel.autoconfigure.actuator;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import dev.aisentinel.autoconfigure.distributed.DistributedThrottleStatus;
import dev.aisentinel.autoconfigure.distributed.training.TrainingPublishStatus;
import dev.aisentinel.autoconfigure.metrics.MicrometerSentinelMetrics;
import dev.aisentinel.core.decision.LastDecisionExplanation;
import dev.aisentinel.core.decision.RiskExplanation;
import dev.aisentinel.core.decision.RiskFactor;
import dev.aisentinel.core.decision.SecurityAdvice;
import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import dev.aisentinel.distributed.quarantine.NoopClusterQuarantineReader;
import dev.aisentinel.distributed.quarantine.NoopClusterQuarantineWriter;
import dev.aisentinel.distributed.throttle.ClusterThrottleStore;
import dev.aisentinel.distributed.throttle.NoopClusterThrottleStore;
import dev.aisentinel.distributed.training.NoopTrainingCandidatePublisher;
import dev.aisentinel.distributed.training.TrainingCandidatePublisher;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.CompositeScoreSnapshotSource;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.StatisticalScoreSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Actuator endpoint {@code /actuator/sentinel}: read-only operational snapshot (config flags, IF state, distributed
 * health, recent score components, last decision explanation, Micrometer summaries when available).
 * <p>
 * {@code lastDecision} is the most recent completed decision observed by <strong>this JVM</strong> — not cluster
 * history. It intentionally omits identity, endpoint, headers, IP, and tokens.
 */
@Slf4j
@Endpoint(id = "sentinel")
public class SentinelActuatorEndpoint {

    private final SentinelProperties props;
    private final CompositeEnforcementHandler enforcementHandlerImpl;
    private final IsolationForestScorer isolationForestScorer;
    private final StartupGrace startupGrace;
    private final MicrometerSentinelMetrics micrometerSentinelMetrics;
    private final CompositeScoreSnapshotSource compositeScoreSnapshotSource;
    private final LastDecisionExplanation lastDecisionExplanation;
    private final DistributedQuarantineStatus distributedQuarantineStatus;
    private final DistributedThrottleStatus distributedThrottleStatus;
    private final ClusterQuarantineReader clusterQuarantineReader;
    private final ClusterQuarantineWriter clusterQuarantineWriter;
    private final ClusterThrottleStore clusterThrottleStore;
    private final TrainingPublishStatus trainingPublishStatus;
    private final TrainingCandidatePublisher trainingCandidatePublisher;

    public SentinelActuatorEndpoint(SentinelProperties props,
                                    CompositeEnforcementHandler enforcementHandlerImpl,
                                    IsolationForestScorer isolationForestScorer,
                                    StartupGrace startupGrace,
                                    MicrometerSentinelMetrics micrometerSentinelMetrics,
                                    CompositeScorer compositeScorer,
                                    DistributedQuarantineStatus distributedQuarantineStatus,
                                    DistributedThrottleStatus distributedThrottleStatus,
                                    ClusterQuarantineReader clusterQuarantineReader,
                                    ClusterQuarantineWriter clusterQuarantineWriter,
                                    ClusterThrottleStore clusterThrottleStore,
                                    ObjectProvider<TrainingPublishStatus> trainingPublishStatusProvider,
                                    ObjectProvider<TrainingCandidatePublisher> trainingCandidatePublisherProvider) {
        this(props, enforcementHandlerImpl, isolationForestScorer, startupGrace, micrometerSentinelMetrics,
            compositeScorer, null, distributedQuarantineStatus, distributedThrottleStatus, clusterQuarantineReader,
            clusterQuarantineWriter, clusterThrottleStore, trainingPublishStatusProvider,
            trainingCandidatePublisherProvider);
    }

    public SentinelActuatorEndpoint(SentinelProperties props,
                                    CompositeEnforcementHandler enforcementHandlerImpl,
                                    IsolationForestScorer isolationForestScorer,
                                    StartupGrace startupGrace,
                                    MicrometerSentinelMetrics micrometerSentinelMetrics,
                                    CompositeScorer compositeScorer,
                                    LastDecisionExplanation lastDecisionExplanation,
                                    DistributedQuarantineStatus distributedQuarantineStatus,
                                    DistributedThrottleStatus distributedThrottleStatus,
                                    ClusterQuarantineReader clusterQuarantineReader,
                                    ClusterQuarantineWriter clusterQuarantineWriter,
                                    ClusterThrottleStore clusterThrottleStore,
                                    ObjectProvider<TrainingPublishStatus> trainingPublishStatusProvider,
                                    ObjectProvider<TrainingCandidatePublisher> trainingCandidatePublisherProvider) {
        this.props = props;
        this.enforcementHandlerImpl = enforcementHandlerImpl;
        this.isolationForestScorer = isolationForestScorer;
        this.startupGrace = startupGrace != null ? startupGrace : StartupGrace.NEVER;
        this.micrometerSentinelMetrics = micrometerSentinelMetrics;
        this.compositeScoreSnapshotSource = compositeScorer;
        this.lastDecisionExplanation = lastDecisionExplanation;
        this.distributedQuarantineStatus = distributedQuarantineStatus;
        this.distributedThrottleStatus = distributedThrottleStatus;
        this.clusterQuarantineReader = clusterQuarantineReader;
        this.clusterQuarantineWriter = clusterQuarantineWriter;
        this.clusterThrottleStore = clusterThrottleStore;
        this.trainingPublishStatus = trainingPublishStatusProvider.getIfAvailable();
        this.trainingCandidatePublisher = trainingCandidatePublisherProvider.getIfAvailable();
    }

    @ReadOperation
    public Map<String, Object> info() {
        log.trace("Actuator /actuator/sentinel info requested");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", props.isEnabled());
        map.put("mode", props.getMode().name());
        map.put("isolationForestEnabled", props.getIsolationForest().isEnabled());
        map.put("startupGraceActive", startupGrace.isGraceActive());
        map.put("enforcementScope", props.getEnforcementScope().name());
        map.put("activeThrottleCount", enforcementHandlerImpl.getThrottleCount());
        map.put("activeQuarantineCount", enforcementHandlerImpl.getQuarantineCount());
        map.put("quarantineCount", enforcementHandlerImpl.getQuarantineCount());
        if (props.getIsolationForest().isEnabled() && isolationForestScorer != null) {
            map.put("isolationForestModelLoaded", isolationForestScorer.isModelLoaded());
            map.put("isolationForestLastScoreMode", isolationForestScorer.lastScoreMode().name());
            map.put("isolationForestActiveModelSource", isolationForestScorer.getActiveModelSource().name());
            map.put("isolationForestBufferedSampleCount", isolationForestScorer.getBufferedSampleCount());
            map.put("isolationForestModelVersion", isolationForestScorer.getModelVersion());
            map.put("isolationForestLastRetrainTimeMillis", isolationForestScorer.getLastRetrainTimeMillis());
            map.put("isolationForestModelAgeMillis", isolationForestScorer.getModelAgeMillis());
            map.put("isolationForestRetrainFailureCount", isolationForestScorer.getRetrainFailureCount());
            map.put("isolationForestLastRetrainFailureTimeMillis", isolationForestScorer.getLastRetrainFailureTimeMillis());
            map.put("acceptedTrainingSampleCount", isolationForestScorer.getAcceptedTrainingSampleCount());
            map.put("rejectedTrainingSampleCount", isolationForestScorer.getRejectedTrainingSampleCount());
            map.put("modelRegistryArtifactVersion", isolationForestScorer.getRegistryArtifactVersion());
            map.put("modelRegistryLastInstallTimeMillis", isolationForestScorer.getLastRegistryInstallTimeMillis());
            map.put("modelRegistryInstallFailureCount", isolationForestScorer.getRegistryInstallFailureCount());
        } else {
            map.put("acceptedTrainingSampleCount", 0L);
            map.put("rejectedTrainingSampleCount", 0L);
        }
        map.put("evaluationStatusModel", Map.of(
            "WARMUP", "STATISTICAL_WARMUP",
            "LIVE", "STATISTICAL_LIVE|COMPLETE",
            "MODEL_FALLBACK", "MODEL_FALLBACK_USED(+MODEL_UNAVAILABLE)",
            "DEGRADED", "DEGRADED",
            "FAIL_OPEN", "FailOpenReason metrics/telemetry (not EvaluationStatus)"
        ));
        if (micrometerSentinelMetrics != null) {
            map.put("scoreSummary", micrometerSentinelMetrics.scoreSummaryForActuator());
            map.put("latencySummary", micrometerSentinelMetrics.latencySummaryForActuator());
            map.put("modelRetrainSuccessCount", micrometerSentinelMetrics.getRetrainSuccessCount());
            map.put("modelRetrainFailureCount", micrometerSentinelMetrics.getRetrainFailureCount());
            map.put("distributedMetrics", micrometerSentinelMetrics.distributedSummaryForActuator());
            map.put("distributedThrottleMetrics", micrometerSentinelMetrics.distributedThrottleSummaryForActuator());
            map.put("distributedTrainingPublishMetrics", micrometerSentinelMetrics.distributedTrainingPublishSummaryForActuator());
        }
        if (micrometerSentinelMetrics != null) {
            map.put("modelRegistryMetrics", micrometerSentinelMetrics.modelRegistrySummaryForActuator());
        }
        map.put("modelRegistryRefreshEnabled", props.getModelRegistry().isRefreshEnabled());
        map.put("modelRegistryFilesystemRootConfigured",
            props.getModelRegistry().getFilesystemRoot() != null && !props.getModelRegistry().getFilesystemRoot().isBlank());
        var d = props.getDistributed();
        map.put("distributedEnabled", d.isEnabled());
        map.put("distributedClusterQuarantineReadEnabled", d.isClusterQuarantineReadEnabled());
        map.put("distributedClusterQuarantineWriteEnabled", d.isClusterQuarantineWriteEnabled());
        map.put("distributedClusterThrottleEnabled", d.isClusterThrottleEnabled());
        map.put("distributedClusterThrottleWindow", d.getClusterThrottleWindow() != null ? d.getClusterThrottleWindow().toMillis() : null);
        map.put("distributedClusterThrottleMaxRequestsPerWindow", d.getClusterThrottleMaxRequestsPerWindow());
        map.put("distributedClusterThrottleMaxInFlight", d.getClusterThrottleMaxInFlight());
        map.put("distributedClusterThrottleTimeoutMillis",
            d.getClusterThrottleTimeout() != null ? d.getClusterThrottleTimeout().toMillis() : null);
        map.put("distributedRedisEnabled", d.getRedis().isEnabled());
        map.put("distributedRedisKeyPrefix", d.getRedis().getKeyPrefix());
        map.put("distributedTrainingPublishEnabled", d.isTrainingPublishEnabled());
        map.put("distributedTrainingKafkaEnabled", d.isTrainingKafkaEnabled());
        map.put("distributedTrainingCandidatesTopic", d.getTrainingCandidatesTopic());
        map.put("distributedTrainingPublishSampleRate", d.getTrainingPublishSampleRate());
        map.put("distributedTrainingPublisherNodeId", d.getTrainingPublisherNodeId());
        if (trainingCandidatePublisher != null) {
            map.put("trainingCandidatePublisherType", trainingCandidatePublisher.getClass().getSimpleName());
            map.put("trainingCandidatePublisherNoop", trainingCandidatePublisher == NoopTrainingCandidatePublisher.INSTANCE);
        }
        if (trainingPublishStatus != null) {
            Map<String, Object> tp = new LinkedHashMap<>();
            tp.put("degraded", trainingPublishStatus.isDegraded());
            tp.put("lastErrorTimeMillis", trainingPublishStatus.getLastErrorTimeMillis());
            tp.put("lastErrorSummary", trainingPublishStatus.getLastErrorSummary());
            map.put("trainingPublish", tp);
        }
        if (clusterQuarantineReader != null) {
            map.put("clusterQuarantineReaderType", clusterQuarantineReader.getClass().getSimpleName());
            map.put("clusterQuarantineReaderNoop",
                clusterQuarantineReader == NoopClusterQuarantineReader.INSTANCE);
        }
        if (clusterQuarantineWriter != null) {
            map.put("clusterQuarantineWriterType", clusterQuarantineWriter.getClass().getSimpleName());
            map.put("clusterQuarantineWriterNoop",
                clusterQuarantineWriter == NoopClusterQuarantineWriter.INSTANCE);
        }
        if (clusterThrottleStore != null) {
            map.put("clusterThrottleStoreType", clusterThrottleStore.getClass().getSimpleName());
            map.put("clusterThrottleStoreNoop", clusterThrottleStore == NoopClusterThrottleStore.INSTANCE);
        }
        if (distributedThrottleStatus != null) {
            Map<String, Object> dt = new LinkedHashMap<>();
            dt.put("redisThrottleDegraded", distributedThrottleStatus.isRedisThrottleDegraded());
            dt.put("lastRedisErrorTimeMillis", distributedThrottleStatus.getLastRedisErrorTimeMillis());
            dt.put("lastRedisErrorSummary", distributedThrottleStatus.getLastRedisErrorSummary());
            map.put("distributedThrottle", dt);
        }
        if (distributedQuarantineStatus != null) {
            Map<String, Object> dq = new LinkedHashMap<>();
            dq.put("redisReaderDegraded", distributedQuarantineStatus.isRedisReaderDegraded());
            dq.put("lastRedisErrorTimeMillis", distributedQuarantineStatus.getLastRedisErrorTimeMillis());
            dq.put("lastRedisErrorSummary", distributedQuarantineStatus.getLastRedisErrorSummary());
            dq.put("redisWriterDegraded", distributedQuarantineStatus.isRedisWriterDegraded());
            dq.put("lastWriteErrorTimeMillis", distributedQuarantineStatus.getLastWriteErrorTimeMillis());
            dq.put("lastWriteErrorSummary", distributedQuarantineStatus.getLastWriteErrorSummary());
            dq.put("approximateQuarantineCacheSize", distributedQuarantineStatus.getApproximateCacheSize());
            map.put("distributedQuarantine", dq);
        }
        map.put("lastScoreComponents", lastScoreComponentsPayload());
        map.put("lastDecision", lastDecisionPayload());
        map.put("lastDecisionScope", "lastCompletedDecisionOnThisJvm");
        return map;
    }

    private Map<String, Object> lastScoreComponentsPayload() {
        if (compositeScoreSnapshotSource == null) {
            return Map.of();
        }
        CompositeScorer.CompositeScoreSnapshot snap = compositeScoreSnapshotSource.getLastCompositeScoreSnapshot();
        if (snap == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statistical", finiteScoreOrNull(snap.statistical()));
        if (snap.isolationForest() != null) {
            m.put("isolationForest", finiteScoreOrNull(snap.isolationForest()));
        }
        m.put("composite", finiteScoreOrNull(snap.composite()));
        m.put("isolationForestIncludedInBlend", snap.isolationForestIncludedInBlend());
        if (snap.isolationForestScoreMode() != null) {
            m.put("isolationForestScoreMode", snap.isolationForestScoreMode());
        }
        m.put("evaluatedAtMillis", snap.evaluatedAtEpochMillis());
        return m;
    }

    private Map<String, Object> lastDecisionPayload() {
        if (lastDecisionExplanation == null) {
            return Map.of();
        }
        LastDecisionExplanation.Snapshot snap = lastDecisionExplanation.get();
        if (snap == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", snap.action());
        // Public/JSON boundary: never emit NaN/Inf as score numbers (Jackson would stringify "NaN").
        // Internal Snapshot may still hold NaN for INVALID_SCORE; null here is unambiguous and
        // cannot be mistaken for legitimate 0.0 or 1.0 risk. Status list carries INVALID_SCORE.
        m.put("anomalyScore", finiteScoreOrNull(snap.anomalyScore()));
        m.put("policyScore", finiteScoreOrNull(snap.policyScore()));
        m.put("policyScoreDiffersFromAnomaly", snap.policyScoreDiffersFromAnomaly());
        m.put("policyBand", snap.action());
        m.put("evaluationStatuses", snap.evaluationStatuses());
        m.put("operatorPhases", snap.operatorPhases());
        if (snap.isolationForestScoreMode() != null) {
            m.put("isolationForestScoreMode", snap.isolationForestScoreMode());
        }
        if (snap.statisticalScore() != null) {
            m.put("statisticalScore", finiteScoreOrNull(snap.statisticalScore()));
        }
        if (snap.isolationForestScore() != null) {
            m.put("isolationForestScore", finiteScoreOrNull(snap.isolationForestScore()));
        }
        if (snap.isolationForestIncludedInBlend() != null) {
            m.put("isolationForestIncludedInBlend", snap.isolationForestIncludedInBlend());
        }
        StatisticalScoreSnapshot se = snap.statisticalExplanation();
        if (se != null) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("score", finiteScoreOrNull(se.score()));
            stat.put("warmup", se.warmup());
            if (se.dominantFeature() != null) {
                stat.put("dominantFeature", se.dominantFeature());
                stat.put("observedValue", finiteScoreOrNull(se.observedValue()));
                stat.put("referenceMean", finiteScoreOrNull(se.referenceMean()));
                stat.put("effectiveStd", finiteScoreOrNull(se.effectiveStd()));
                stat.put("rawAbsZ", finiteScoreOrNull(se.rawAbsZ()));
                stat.put("cappedAbsZ", finiteScoreOrNull(se.cappedAbsZ()));
            }
            m.put("statisticalExplanation", stat);
        }
        m.put("startupGraceActive", snap.startupGraceActive());
        m.put("evaluatedAtMillis", snap.evaluatedAtEpochMillis());
        RiskExplanation explanation = snap.explanation();
        if (explanation != null && !explanation.isEmpty()) {
            m.put("riskFactors", riskFactorsPayload(explanation));
            if (explanation.advice() != null) {
                m.put("securityAdvice", securityAdvicePayload(explanation.advice()));
            }
        } else if (explanation != null) {
            // Empty explanation: explicit empty list; omit advice key (absent vs empty advice).
            m.put("riskFactors", List.of());
        }
        return m;
    }

    private static List<Map<String, Object>> riskFactorsPayload(RiskExplanation explanation) {
        List<Map<String, Object>> out = new ArrayList<>(explanation.factors().size());
        for (RiskFactor factor : explanation.factors()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("code", factor.code().name());
            f.put("category", factor.category().name());
            f.put("severity", factor.severity().name());
            f.put("contribution", finiteScoreOrNull(factor.contribution()));
            f.put("confidence", finiteScoreOrNull(factor.confidence()));
            f.put("evidenceRef", factor.evidenceRef());
            f.put("explanation", factor.explanation());
            f.put("source", factor.source());
            out.add(f);
        }
        return out;
    }

    private static Map<String, Object> securityAdvicePayload(SecurityAdvice advice) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("code", advice.code().name());
        a.put("priority", advice.priority().name());
        a.put("reason", advice.reason());
        a.put("linkedFactorCodes", advice.linkedFactorCodes().stream().map(Enum::name).toList());
        a.put("humanReviewRecommended", advice.humanReviewRecommended());
        return a;
    }

    /** Presentation-only: finite scores pass through; NaN/±Infinity become {@code null}. */
    static Double finiteScoreOrNull(double score) {
        return Double.isFinite(score) ? score : null;
    }
}
