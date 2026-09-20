package dev.aisentinel.benchmark.deployment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

record ResourceBenchmarkConfig(
    String mode,
    Path resultsDir,
    Duration warmupDuration,
    Duration measurementDuration,
    Duration samplingInterval,
    List<Integer> inProcessConcurrency,
    List<Integer> remoteConcurrency,
    List<Integer> redisConcurrency
) {

    public ResourceBenchmarkConfig {
        mode = normalize(mode);
        Objects.requireNonNull(resultsDir, "resultsDir");
        requirePositive(warmupDuration, "warmupDuration");
        requirePositive(measurementDuration, "measurementDuration");
        requirePositive(samplingInterval, "samplingInterval");
        inProcessConcurrency = List.copyOf(inProcessConcurrency);
        remoteConcurrency = List.copyOf(remoteConcurrency);
        redisConcurrency = List.copyOf(redisConcurrency);
    }

    static ResourceBenchmarkConfig forMode(String mode, Path resultsDir) {
        String normalized = normalize(mode);
        return switch (normalized) {
            case "smoke" -> new ResourceBenchmarkConfig(
                normalized, resultsDir,
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofMillis(250),
                List.of(1), List.of(1), List.of(1));
            case "in-process" -> new ResourceBenchmarkConfig(
                normalized, resultsDir,
                Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofMillis(250),
                List.of(1, 4, 16), List.of(1), List.of(1));
            case "remote" -> new ResourceBenchmarkConfig(
                normalized, resultsDir,
                Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofMillis(250),
                List.of(1), List.of(1, 4, 16), List.of(1));
            case "redis" -> new ResourceBenchmarkConfig(
                normalized, resultsDir,
                Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofMillis(250),
                List.of(1), List.of(1), List.of(1, 4, 16));
            case "full" -> new ResourceBenchmarkConfig(
                normalized, resultsDir,
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMillis(250),
                List.of(1, 4, 16), List.of(1, 4, 16), List.of(1, 4, 16));
            default -> throw new IllegalArgumentException("Unsupported resource mode: " + mode);
        };
    }

    boolean runInProcess() {
        return mode.equals("smoke") || mode.equals("in-process") || mode.equals("full");
    }

    boolean runRemote() {
        return mode.equals("smoke") || mode.equals("remote") || mode.equals("full");
    }

    boolean runRedis() {
        return mode.equals("smoke") || mode.equals("redis") || mode.equals("full");
    }

    private static String normalize(String mode) {
        String normalized = mode == null ? "smoke" : mode.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "smoke" : normalized;
    }

    private static void requirePositive(Duration duration, String name) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
