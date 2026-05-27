package io.aisentinel.core.telemetry;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DefaultTelemetryEmitterTest {

    private static final class LogCapture implements AutoCloseable {
        private final Logger logback;
        final ListAppender<ILoggingEvent> appender;

        LogCapture() {
            logback = (Logger) LoggerFactory.getLogger(DefaultTelemetryEmitter.class);
            appender = new ListAppender<>();
            appender.setContext(logback.getLoggerContext());
            appender.start();
            logback.addAppender(appender);
        }

        String joinedMessages() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"));
        }

        @Override
        public void close() {
            logback.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void nullRegistryAndConfigUseSafeDefaults() {
        assertThatCode(() -> {
            DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(null, null);
            emitter.emit(TelemetryEvent.threatScored("abcdabcd", "/p", 0.1));
        }).doesNotThrowAnyException();
    }

    @Test
    void emitWithNoneVerbosityDoesNotLogJson() {
        try (LogCapture cap = new LogCapture()) {
            MeterRegistry registry = new SimpleMeterRegistry();
            TelemetryConfig cfg = new TelemetryConfig(TelemetryConfig.LogVerbosity.NONE, 0.9, 10);
            DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, cfg);

            emitter.emit(TelemetryEvent.policyActionApplied("hashhash12", "/x", "MONITOR", null));

            assertThat(cap.appender.list).isEmpty();
        }
    }

    @Test
    void fullVerbosityLogsEveryEventType() {
        try (LogCapture cap = new LogCapture()) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TelemetryConfig cfg = new TelemetryConfig(TelemetryConfig.LogVerbosity.FULL, 0.99, 10);
            DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, cfg);

            emitter.emit(new TelemetryEvent("CustomType", 42L, Map.of("x", 1)));

            assertThat(cap.joinedMessages()).contains("\"type\":\"CustomType\"").contains("\"timestamp\":42");
        }
    }

    @Test
    void anomalyOnlyLogsThreatWhenScoreMeetsThreshold() {
        try (LogCapture cap = new LogCapture()) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TelemetryConfig cfg = new TelemetryConfig(TelemetryConfig.LogVerbosity.ANOMALY_ONLY, 0.8, 10);
            DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, cfg);

            emitter.emit(new TelemetryEvent("ThreatScored", 1L, Map.of("score", 0.5)));
            assertThat(cap.appender.list).isEmpty();

            emitter.emit(new TelemetryEvent("ThreatScored", 2L, Map.of("score", 0.8)));
            assertThat(cap.joinedMessages()).contains("ThreatScored").contains("\"score\":0.8");
        }
    }

    @Test
    void anomalyOnlyLogsPolicyWhenActionIsNotMonitor() {
        try (LogCapture cap = new LogCapture()) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TelemetryConfig cfg = new TelemetryConfig(TelemetryConfig.LogVerbosity.ANOMALY_ONLY, 0.9, 10);
            DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, cfg);

            emitter.emit(new TelemetryEvent("PolicyActionApplied", 1L, Map.of("action", "MONITOR")));
            assertThat(cap.appender.list).isEmpty();

            emitter.emit(new TelemetryEvent("PolicyActionApplied", 2L, Map.of("action", "BLOCK")));
            assertThat(cap.joinedMessages()).contains("PolicyActionApplied").contains("BLOCK");
        }
    }

    @Test
    void anomalyOnlyAlwaysLogsQuarantineAndAnomaly() {
        try (LogCapture cap = new LogCapture()) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TelemetryConfig cfg = new TelemetryConfig(TelemetryConfig.LogVerbosity.ANOMALY_ONLY, 0.99, 10);
            DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, cfg);

            emitter.emit(new TelemetryEvent("QuarantineStarted", 9L, Map.of("durationMs", 1000)));
            emitter.emit(new TelemetryEvent("AnomalyDetected", 10L, Map.of("score", 0.01)));

            String out = cap.joinedMessages();
            assertThat(out).contains("QuarantineStarted").contains("AnomalyDetected");
        }
    }

    @Test
    void anomalyOnlySkipsUnknownEventTypes() {
        try (LogCapture cap = new LogCapture()) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TelemetryConfig cfg = new TelemetryConfig(TelemetryConfig.LogVerbosity.ANOMALY_ONLY, 0.1, 10);
            DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, cfg);

            emitter.emit(new TelemetryEvent("Noise", 1L, Map.of("a", 1)));
            assertThat(cap.appender.list).isEmpty();
        }
    }

    @Test
    void sampledLogsEveryNthEmit() {
        try (LogCapture cap = new LogCapture()) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TelemetryConfig cfg = new TelemetryConfig(TelemetryConfig.LogVerbosity.SAMPLED, 0.4, 2);
            DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, cfg);

            emitter.emit(TelemetryEvent.threatScored("abcdabcd", "/a", 0.1));
            assertThat(cap.appender.list).isEmpty();

            emitter.emit(TelemetryEvent.threatScored("abcdabcd", "/b", 0.2));
            assertThat(cap.joinedMessages()).contains("ThreatScored");
        }
    }

    @Test
    void jsonPayloadEscapesQuotesAndBackslashesAndSerializesPrimitives() {
        try (LogCapture cap = new LogCapture()) {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, TelemetryConfig.defaults());

            Map<String, Object> payload = new HashMap<>();
            payload.put("s", "a\"b\\c");
            payload.put("n", 3);
            payload.put("b", true);
            payload.put("z", null);
            emitter.emit(new TelemetryEvent("E", 1L, payload));

            String msg = cap.joinedMessages();
            assertThat(msg).contains("\\\"");
            assertThat(msg).contains("\"n\":3").contains("\"b\":true").contains("\"z\":null");
        }
    }

    @Test
    void emitIncrementsCounterForThreatScored() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultTelemetryEmitter emitter = new DefaultTelemetryEmitter(registry, TelemetryConfig.defaults());

        emitter.emit(new TelemetryEvent("ThreatScored", 1L, Map.of("identityHash", "ab", "endpoint", "/", "score", 0.1)));

        assertThat(registry.counter("sentinel.events", "type", "ThreatScored").count()).isEqualTo(1.0);
    }
}
