package io.aisentinel.autoconfigure.actuator;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.metrics.MicrometerSentinelMetrics;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.scoring.IsolationForestScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Endpoint(id = "sentinel")
public class SentinelActuatorEndpoint {

    private final SentinelProperties props;
    private final CompositeEnforcementHandler enforcementHandlerImpl;
    private final IsolationForestScorer isolationForestScorer;
    private final StartupGrace startupGrace;
    private final MicrometerSentinelMetrics micrometerSentinelMetrics;

    public SentinelActuatorEndpoint(SentinelProperties props,
                                    CompositeEnforcementHandler enforcementHandlerImpl,
                                    IsolationForestScorer isolationForestScorer,
                                    StartupGrace startupGrace,
                                    MicrometerSentinelMetrics micrometerSentinelMetrics) {
        this.props = props;
        this.enforcementHandlerImpl = enforcementHandlerImpl;
        this.isolationForestScorer = isolationForestScorer;
        this.startupGrace = startupGrace != null ? startupGrace : StartupGrace.NEVER;
        this.micrometerSentinelMetrics = micrometerSentinelMetrics;
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
        } else {
            map.put("acceptedTrainingSampleCount", 0L);
            map.put("rejectedTrainingSampleCount", 0L);
        }
        if (micrometerSentinelMetrics != null) {
            map.put("scoreSummary", micrometerSentinelMetrics.scoreSummaryForActuator());
            map.put("latencySummary", micrometerSentinelMetrics.latencySummaryForActuator());
            map.put("modelRetrainSuccessCount", micrometerSentinelMetrics.getRetrainSuccessCount());
            map.put("modelRetrainFailureCount", micrometerSentinelMetrics.getRetrainFailureCount());
        }
        return map;
    }
}
