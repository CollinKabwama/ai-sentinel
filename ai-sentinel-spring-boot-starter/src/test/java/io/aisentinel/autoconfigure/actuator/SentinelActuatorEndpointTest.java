package io.aisentinel.autoconfigure.actuator;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.core.scoring.BoundedTrainingBuffer;
import io.aisentinel.core.scoring.IsolationForestConfig;
import io.aisentinel.core.scoring.IsolationForestScorer;
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
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), null);

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
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), null);

        Map<String, Object> info = endpoint.info();

        assertThat(info.get("enabled")).isEqualTo(false);
        assertThat(info.get("mode")).isEqualTo("MONITOR");
        assertThat(info.get("isolationForestEnabled")).isEqualTo(true);
        assertThat(info.get("quarantineCount")).isEqualTo(0);
    }

    @Test
    void infoIncludesIsolationForestMetadataWhenEnabledAndScorerProvided() {
        SentinelProperties props = new SentinelProperties();
        props.getIsolationForest().setEnabled(true);
        var buffer = new BoundedTrainingBuffer(100);
        var config = new IsolationForestConfig(0.5, 10, 5, 5, 42L, 0.1);
        IsolationForestScorer ifScorer = new IsolationForestScorer(buffer, config);
        SentinelActuatorEndpoint endpoint = new SentinelActuatorEndpoint(props, compositeHandler(), ifScorer);

        Map<String, Object> info = endpoint.info();

        assertThat(info).containsKeys("isolationForestModelLoaded", "isolationForestBufferedSampleCount",
            "isolationForestModelVersion", "isolationForestLastRetrainTimeMillis",
            "isolationForestModelAgeMillis", "isolationForestRetrainFailureCount",
            "isolationForestLastRetrainFailureTimeMillis");
        assertThat(info.get("isolationForestModelLoaded")).isEqualTo(false);
        assertThat(info.get("isolationForestBufferedSampleCount")).isEqualTo(0);
        assertThat(info.get("isolationForestModelAgeMillis")).isEqualTo(-1L);
        assertThat(info.get("isolationForestRetrainFailureCount")).isEqualTo(0L);
    }
}
