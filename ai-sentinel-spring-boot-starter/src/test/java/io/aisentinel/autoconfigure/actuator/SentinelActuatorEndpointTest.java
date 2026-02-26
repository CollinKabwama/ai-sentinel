package io.aisentinel.autoconfigure.actuator;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SentinelActuatorEndpointTest {

    private static CompositeEnforcementHandler compositeHandler() {
        return new CompositeEnforcementHandler(429, 60_000L, 5.0, mock(TelemetryEmitter.class));
    }

    @Test
    void infoReturnsExpectedStructure() {
        SentinelProperties props = new SentinelProperties();
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler());

        Map<String, Object> info = endpoint.info();

        assertThat(info).containsKeys("enabled", "mode", "isolationForestEnabled", "quarantineCount");
        assertThat(info.get("enabled")).isEqualTo(true);
        assertThat(info.get("mode")).isEqualTo("ENFORCE");
        assertThat(info.get("isolationForestEnabled")).isEqualTo(false);
        assertThat(info.get("quarantineCount")).isEqualTo(0);
    }

    @Test
    void infoReflectsCustomProperties() {
        SentinelProperties props = new SentinelProperties();
        props.setEnabled(false);
        props.setMode(SentinelProperties.Mode.MONITOR);
        props.getIsolationForest().setEnabled(true);
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler());

        Map<String, Object> info = endpoint.info();

        assertThat(info.get("enabled")).isEqualTo(false);
        assertThat(info.get("mode")).isEqualTo("MONITOR");
        assertThat(info.get("isolationForestEnabled")).isEqualTo(true);
        assertThat(info.get("quarantineCount")).isEqualTo(0);
    }
}
