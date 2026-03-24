package io.aisentinel.autoconfigure.actuator;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
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

    public SentinelActuatorEndpoint(SentinelProperties props,
                                    CompositeEnforcementHandler enforcementHandlerImpl,
                                    IsolationForestScorer isolationForestScorer) {
        this.props = props;
        this.enforcementHandlerImpl = enforcementHandlerImpl;
        this.isolationForestScorer = isolationForestScorer;
    }

    @ReadOperation
    public Map<String, Object> info() {
        log.trace("Actuator /actuator/sentinel info requested");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", props.isEnabled());
        map.put("mode", props.getMode().name());
        map.put("isolationForestEnabled", props.getIsolationForest().isEnabled());
        map.put("quarantineCount", enforcementHandlerImpl.getQuarantineCount());
        if (props.getIsolationForest().isEnabled() && isolationForestScorer != null) {
            map.put("isolationForestModelLoaded", isolationForestScorer.isModelLoaded());
            map.put("isolationForestBufferedSampleCount", isolationForestScorer.getBufferedSampleCount());
            map.put("isolationForestModelVersion", isolationForestScorer.getModelVersion());
            map.put("isolationForestLastRetrainTimeMillis", isolationForestScorer.getLastRetrainTimeMillis());
            map.put("isolationForestModelAgeMillis", isolationForestScorer.getModelAgeMillis());
            map.put("isolationForestRetrainFailureCount", isolationForestScorer.getRetrainFailureCount());
            map.put("isolationForestLastRetrainFailureTimeMillis", isolationForestScorer.getLastRetrainFailureTimeMillis());
        }
        return map;
    }
}
