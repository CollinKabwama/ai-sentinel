package dev.aisentinel.core.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Emits telemetry via JSON logs and Micrometer metrics.
 * Log verbosity is configurable: FULL, ANOMALY_ONLY, SAMPLED, NONE.
 */
public final class DefaultTelemetryEmitter implements TelemetryEmitter {

    private static final Logger log = LoggerFactory.getLogger(DefaultTelemetryEmitter.class);
    private static final Set<String> CACHEABLE_EVENT_TYPES = Set.of(
        "ThreatScored",
        "AnomalyDetected",
        "PolicyActionApplied",
        "QuarantineStarted",
        "QuarantineReleased",
        "FailOpen"
    );

    private final MeterRegistry registry;
    private final TelemetryConfig config;
    private final AtomicLong emitCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, Counter> countersByEventType = new ConcurrentHashMap<>();

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
                log.info("ai-sentinel: {}", formatEventJson(event));
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

    static String formatEventJson(TelemetryEvent event) {
        StringBuilder sb = new StringBuilder(128 + event.payload().size() * 24);
        sb.append("{\"type\":\"").append(escapeJson(event.type()))
            .append("\",\"timestamp\":").append(event.timestampMillis())
            .append(",\"payload\":{");
        appendPayloadJson(sb, event.payload());
        sb.append("}}");
        return sb.toString();
    }

    private static void appendPayloadJson(StringBuilder sb, Map<String, Object> payload) {
        boolean first = true;
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJson(entry.getKey())).append("\":").append(jsonValue(entry.getValue()));
        }
    }

    private static String jsonValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        return "\"" + escapeJson(v.toString()) + "\"";
    }

    private static String escapeJson(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private void recordMetric(TelemetryEvent event) {
        try {
            String type = event.type();
            Counter counter = CACHEABLE_EVENT_TYPES.contains(type)
                ? countersByEventType.computeIfAbsent(type, this::registerCounter)
                : registerCounter(type);
            counter.increment();
        } catch (Exception ignored) {
            // fail-open telemetry
        }
    }

    private Counter registerCounter(String type) {
        return Counter.builder("sentinel.events").tag("type", type).register(registry);
    }

    int cachedCounterCount() {
        return countersByEventType.size();
    }
}
