package io.aisentinel.core.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DefaultTelemetryEmitterTest {

    @Test
    void emitWithNoneVerbosityDoesNotThrow() {
        MeterRegistry registry = new SimpleMeterRegistry();
        TelemetryConfig cfg = new TelemetryConfig(TelemetryConfig.LogVerbosity.NONE, 0.9, 10);
        DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, cfg);

        assertThatCode(() -> emitter.emit(TelemetryEvent.policyActionApplied("hashhash12", "/x", "MONITOR", null)))
            .doesNotThrowAnyException();
    }

    @Test
    void emitIncrementsCounterForThreatScored() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, TelemetryConfig.defaults());

        emitter.emit(new TelemetryEvent("ThreatScored", 1L, Map.of("identityHash", "ab", "endpoint", "/", "score", 0.1)));

        assertThat(registry.counter("sentinel.events", "type", "ThreatScored").count()).isEqualTo(1.0);
    }
}
