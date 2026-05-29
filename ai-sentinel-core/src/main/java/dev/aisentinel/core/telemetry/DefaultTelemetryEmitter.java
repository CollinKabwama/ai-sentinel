package dev.aisentinel.core.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Emits telemetry via JSON logs and Micrometer metrics.
 * Log verbosity is configurable: FULL, ANOMALY_ONLY, SAMPLED, NONE.
 */
public final class DefaultTelemetryEmitter implements TelemetryEmitter {

    private static final Logger log = LoggerFactory.getLogger(DefaultTelemetryEmitter.class);

    private final MeterRegistry registry;
    private final TelemetryConfig config;
    private final AtomicLong emitCounter = new AtomicLong(0);

    public DefaultTelemetryEmitter(MeterRegistry registry) {
        this(registry, TelemetryConfig.defaults());
    }

    public DefaultTelemetryEmitter(MeterRegistry registry, TelemetryConfig config) {
        this.registry = registry != null ? registry : new SimpleMeterRegistry();
        this.config = config != null ? config : TelemetryConfig.defaults();
    }

    @Override
    public void emit(TelemetryEvent event) {
        try {
            if (shouldLog(event)) {
                String payloadStr = event.payload().entrySet().stream()
                    .map(e -> "\"" + e.getKey() + "\":" + jsonValue(e.getValue()))
                    .collect(Collectors.joining(","));
                String json = "{\"type\":\"" + event.type() + "\",\"timestamp\":" + event.timestampMillis() + ",\"payload\":{" + payloadStr + "}}";
                log.info("ai-sentinel: {}", json);
            }
            recordMetric(event);
        } catch (Exception e) {
            log.debug("Telemetry emit failed", e);
        }
    }

    private boolean shouldLog(TelemetryEvent event) {
        return switch (config.logVerbosity()) {
            case NONE -> false;
            case FULL -> true;
            case ANOMALY_ONLY -> isAnomalousEvent(event);
            case SAMPLED -> emitCounter.incrementAndGet() % config.logSampleRate() == 0;
        };
    }

    private boolean isAnomalousEvent(TelemetryEvent event) {
        return switch (event.type()) {
            case "ThreatScored" -> {
                Object s = event.payload().get("score");
                yield s instanceof Number n && n.doubleValue() >= config.logScoreThreshold();
            }
            case "AnomalyDetected", "QuarantineStarted" -> true;
            case "PolicyActionApplied" -> {
                Object a = event.payload().get("action");
                String action = a != null ? a.toString() : "";
                yield !"MONITOR".equals(action);
            }
            default -> false;
        };
    }

    private static String jsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof Number) return v.toString();
        if (v instanceof Boolean) return v.toString();
        return "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void recordMetric(TelemetryEvent event) {
        try {
            Counter.builder("sentinel.events")
                .tag("type", event.type())
                .register(registry)
                .increment();
        } catch (Exception ignored) { }
    }
}
