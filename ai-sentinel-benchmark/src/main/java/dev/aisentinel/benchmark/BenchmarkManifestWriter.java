package dev.aisentinel.benchmark;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Writes AI-Sentinel benchmark environment manifests as JSON.
 * JMH's own JSON result file is retained separately; this document records suite metadata.
 */
public final class BenchmarkManifestWriter {

    private BenchmarkManifestWriter() {
    }

    public static void write(Path destination, EnvironmentMetadata metadata, Map<String, String> extras)
        throws IOException {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(metadata, "metadata");
        Map<String, String> safeExtras = extras == null ? Map.of() : extras;

        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        StringBuilder json = new StringBuilder(512);
        json.append("{\n");
        appendField(json, "benchmarkSuiteVersion", metadata.suiteFormatVersion(), true);
        appendField(json, "suiteName", metadata.suiteName(), true);
        appendField(json, "sentinelVersion", metadata.sentinelVersion(), true);
        appendField(json, "commit", metadata.gitCommit(), true);
        appendField(json, "capturedAtUtc", metadata.capturedAtUtc(), true);
        appendField(json, "featureSchemaVersion", metadata.featureSchemaVersion(), true);
        appendField(json, "deploymentMode", metadata.deploymentMode(), true);
        appendField(json, "stateBackend", metadata.stateBackend(), true);
        json.append("  \"environment\": {\n");
        appendNested(json, "javaVersion", metadata.javaVersion(), true);
        appendNested(json, "javaVendor", metadata.javaVendor(), true);
        appendNested(json, "javaVmName", metadata.javaVmName(), true);
        appendNested(json, "os", metadata.osName(), true);
        appendNested(json, "osVersion", metadata.osVersion(), true);
        appendNested(json, "architecture", metadata.osArch(), true);
        appendNestedNumber(json, "processors", metadata.availableProcessors(), true);
        appendNestedNumber(json, "maxHeapBytes", metadata.maxHeapBytes(), true);
        appendNestedNumber(json, "totalMemoryBytes", metadata.totalMemoryBytes(), true);
        appendNested(json, "jvmInputArguments", metadata.jvmInputArguments(), false);
        json.append("\n  }");
        if (!safeExtras.isEmpty()) {
            json.append(",\n  \"run\": {\n");
            int i = 0;
            for (Map.Entry<String, String> e : new LinkedHashMap<>(safeExtras).entrySet()) {
                boolean more = ++i < safeExtras.size();
                appendNested(json, e.getKey(), e.getValue(), more);
                if (more) {
                    json.append('\n');
                }
            }
            json.append("\n  }");
        }
        json.append(",\n  \"disclaimer\": ");
        json.append(quote(
            "Synthetic host-local measurements. Not a production SLA, partner guarantee, or detection-quality claim."));
        json.append("\n}\n");

        try (Writer writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            writer.write(json.toString());
        }
    }

    private static void appendField(StringBuilder json, String name, String value, boolean trailingComma) {
        json.append("  ").append(quote(name)).append(": ").append(value == null ? "null" : quote(value));
        if (trailingComma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static void appendNested(StringBuilder json, String name, String value, boolean trailingComma) {
        json.append("    ").append(quote(name)).append(": ").append(value == null ? "null" : quote(value));
        if (trailingComma) {
            json.append(',');
        }
        json.append('\n');
    }

    private static void appendNestedNumber(StringBuilder json, String name, Number value, boolean trailingComma) {
        json.append("    ").append(quote(name)).append(": ").append(value == null ? "null" : value.toString());
        if (trailingComma) {
            json.append(',');
        }
        json.append('\n');
    }

    static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        out.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
