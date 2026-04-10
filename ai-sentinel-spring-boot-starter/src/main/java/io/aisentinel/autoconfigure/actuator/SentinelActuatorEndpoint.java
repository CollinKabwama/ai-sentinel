package io.aisentinel.autoconfigure.actuator;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.autoconfigure.distributed.DistributedThrottleStatus;
import io.aisentinel.autoconfigure.distributed.training.TrainingPublishStatus;
import io.aisentinel.autoconfigure.metrics.MicrometerSentinelMetrics;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import io.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import io.aisentinel.distributed.quarantine.NoopClusterQuarantineReader;
import io.aisentinel.distributed.quarantine.NoopClusterQuarantineWriter;
import io.aisentinel.distributed.throttle.ClusterThrottleStore;
import io.aisentinel.distributed.throttle.NoopClusterThrottleStore;
import io.aisentinel.distributed.training.NoopTrainingCandidatePublisher;
import io.aisentinel.distributed.training.TrainingCandidatePublisher;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.scoring.CompositeScorer;
import io.aisentinel.core.scoring.IsolationForestScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator endpoint {@code /actuator/sentinel}: read-only operational snapshot (config flags, IF state, distributed
 * health, recent score components, Micrometer summaries when available).
 */
@Slf4j
@Endpoint(id = "sentinel")
public class SentinelActuatorEndpoint {

    private final SentinelProperties props;
    private final CompositeEnforcementHandler enforcementHandlerImpl;
    private final IsolationForestScorer isolationForestScorer;
    private final StartupGrace startupGrace;
    private final MicrometerSentinelMetrics micrometerSentinelMetrics;
    private final CompositeScorer compositeScorer;
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
        this.props = props;
        this.enforcementHandlerImpl = enforcementHandlerImpl;
        this.isolationForestScorer = isolationForestScorer;
        this.startupGrace = startupGrace != null ? startupGrace : StartupGrace.NEVER;
        this.micrometerSentinelMetrics = micrometerSentinelMetrics;
        this.compositeScorer = compositeScorer;
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
        return map;
    }

    private Map<String, Object> lastScoreComponentsPayload() {
        if (compositeScorer == null) {
            return Map.of();
        }
        CompositeScorer.CompositeScoreSnapshot snap = compositeScorer.getLastCompositeScoreSnapshot();
        if (snap == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statistical", Double.isNaN(snap.statistical()) ? null : snap.statistical());
        if (snap.isolationForest() != null) {
            m.put("isolationForest", snap.isolationForest());
        }
        m.put("composite", snap.composite());
        m.put("evaluatedAtMillis", snap.evaluatedAtEpochMillis());
        return m;
    }
}
