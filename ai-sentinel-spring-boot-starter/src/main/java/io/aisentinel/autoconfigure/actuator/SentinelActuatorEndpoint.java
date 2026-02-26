package io.aisentinel.autoconfigure.actuator;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Endpoint(id = "sentinel")
@RequiredArgsConstructor
public class SentinelActuatorEndpoint {

    private final SentinelProperties props;
    private final CompositeEnforcementHandler enforcementHandlerImpl;

    @ReadOperation
    public Map<String, Object> info() {
        log.trace("Actuator /actuator/sentinel info requested");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", props.isEnabled());
        map.put("mode", props.getMode().name());
        map.put("isolationForestEnabled", props.getIsolationForest().isEnabled());
        map.put("quarantineCount", enforcementHandlerImpl.getQuarantineCount());
        return map;
    }
}
