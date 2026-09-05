package dev.aisentinel.benchmark.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class ResourceSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ResourceSupport() {
    }

    static long heapUsedBytes() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        MemoryUsage usage = bean.getHeapMemoryUsage();
        return usage.getUsed();
    }

    static long heapCommittedBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getCommitted();
    }

    static long heapMaxBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
    }

    static CpuSnapshot cpuSnapshot() {
        java.lang.management.OperatingSystemMXBean raw = ManagementFactory.getOperatingSystemMXBean();
        if (raw instanceof com.sun.management.OperatingSystemMXBean os) {
            return new CpuSnapshot(os.getProcessCpuTime(), os.getAvailableProcessors());
        }
        return new CpuSnapshot(null, raw.getAvailableProcessors());
    }

    static GcSnapshot gcSnapshot() {
        long count = 0L;
        long time = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            long t = bean.getCollectionTime();
            if (c >= 0) {
                count += c;
            }
            if (t >= 0) {
                time += t;
            }
        }
        return new GcSnapshot(count, time);
    }

    static Long processRssBytes() {
        String pid = Long.toString(ProcessHandle.current().pid());
        String output = commandOutput(List.of("ps", "-o", "rss=", "-p", pid));
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(output.trim()) * 1024L;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Boolean dirtyTree() {
        String output = commandOutput(List.of("git", "status", "--porcelain"));
        return output != null ? !output.isBlank() : null;
    }

    static DockerStats dockerStats(String containerId) {
        String output = commandOutput(List.of(
            "docker", "stats", "--no-stream", "--format", "{{json .}}", containerId));
        if (output == null || output.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(output);
            String cpu = node.path("CPUPerc").asText("");
            String memory = node.path("MemUsage").asText("");
            return new DockerStats(parsePercent(cpu), parseMemoryUsage(memory));
        } catch (Exception ignored) {
            return null;
        }
    }

    static Double processCpuCoresEquivalent(Long cpuDeltaNanos, long wallDeltaNanos) {
        if (cpuDeltaNanos == null || wallDeltaNanos <= 0L) {
            return null;
        }
        return cpuDeltaNanos / (double) wallDeltaNanos;
    }

    static Double processCpuPercentOfMachine(Long cpuDeltaNanos, long wallDeltaNanos, int logicalProcessors) {
        if (cpuDeltaNanos == null || wallDeltaNanos <= 0L || logicalProcessors <= 0) {
            return null;
        }
        return (cpuDeltaNanos / (double) wallDeltaNanos) / logicalProcessors * 100.0;
    }

    private static Double parsePercent(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replace("%", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long parseMemoryUsage(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String left = raw.split("/")[0].trim();
        if (left.isEmpty()) {
            return null;
        }
        String numeric = left.replaceAll("[^0-9.]", "");
        String unit = left.replaceAll("[0-9.\\s]", "");
        if (numeric.isEmpty() || unit.isEmpty()) {
            return null;
        }
        double value = Double.parseDouble(numeric);
        long multiplier = switch (unit) {
            case "B" -> 1L;
            case "KiB", "kB" -> 1024L;
            case "MiB", "MB" -> 1024L * 1024L;
            case "GiB", "GB" -> 1024L * 1024L * 1024L;
            default -> 1L;
        };
        return Math.round(value * multiplier);
    }

    private static String commandOutput(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce("", (a, b) -> a + (a.isEmpty() ? "" : "\n") + b).trim();
            }
            int exit = process.waitFor();
            return exit == 0 ? output : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    record CpuSnapshot(Long processCpuTimeNanos, int logicalProcessors) {
    }

    record GcSnapshot(long collectionCount, long collectionTimeMillis) {
    }

    record DockerStats(Double cpuPercent, Long memoryBytes) {
    }
}
