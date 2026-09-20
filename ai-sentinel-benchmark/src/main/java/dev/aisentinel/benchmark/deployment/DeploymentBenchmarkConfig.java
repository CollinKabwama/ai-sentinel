package dev.aisentinel.benchmark.deployment;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Small explicit config surface for the deployment benchmark runner.
 */
public record DeploymentBenchmarkConfig(
    String mode,
    Path resultsDir,
    int warmupAttemptsPerThread,
    int measuredAttemptsPerThread,
    List<Integer> remoteConcurrencyLevels,
    List<Integer> redisConcurrencyLevels,
    Duration remoteConnectTimeout,
    Duration remoteReadTimeout,
    Duration redisCommandTimeout
) {

    public DeploymentBenchmarkConfig {
        mode = normalizeMode(mode);
        Objects.requireNonNull(resultsDir, "resultsDir");
        remoteConcurrencyLevels = List.copyOf(remoteConcurrencyLevels);
        redisConcurrencyLevels = List.copyOf(redisConcurrencyLevels);
        if (warmupAttemptsPerThread < 0) {
            throw new IllegalArgumentException("warmupAttemptsPerThread must be >= 0");
        }
        if (measuredAttemptsPerThread <= 0) {
            throw new IllegalArgumentException("measuredAttemptsPerThread must be > 0");
        }
        requirePositive(remoteConcurrencyLevels, "remoteConcurrencyLevels");
        requirePositive(redisConcurrencyLevels, "redisConcurrencyLevels");
        requirePositive(remoteConnectTimeout, "remoteConnectTimeout");
        requirePositive(remoteReadTimeout, "remoteReadTimeout");
        requirePositive(redisCommandTimeout, "redisCommandTimeout");
    }

    static DeploymentBenchmarkConfig forMode(String mode, Path resultsDir) {
        String normalized = normalizeMode(mode);
        return switch (normalized) {
            case "smoke" -> new DeploymentBenchmarkConfig(
                normalized,
                resultsDir,
                15,
                60,
                List.of(1),
                List.of(1),
                Duration.ofMillis(150),
                Duration.ofMillis(400),
                Duration.ofMillis(150));
            case "remote" -> new DeploymentBenchmarkConfig(
                normalized,
                resultsDir,
                40,
                300,
                List.of(1, 4, 16),
                List.of(1),
                Duration.ofMillis(150),
                Duration.ofMillis(500),
                Duration.ofMillis(150));
            case "redis" -> new DeploymentBenchmarkConfig(
                normalized,
                resultsDir,
                40,
                220,
                List.of(1),
                List.of(1, 4, 16),
                Duration.ofMillis(150),
                Duration.ofMillis(500),
                Duration.ofMillis(150));
            case "degradation" -> new DeploymentBenchmarkConfig(
                normalized,
                resultsDir,
                10,
                24,
                List.of(1),
                List.of(1),
                Duration.ofMillis(100),
                Duration.ofMillis(220),
                Duration.ofMillis(120));
            case "full" -> new DeploymentBenchmarkConfig(
                normalized,
                resultsDir,
                40,
                300,
                List.of(1, 4, 16),
                List.of(1, 4, 16),
                Duration.ofMillis(150),
                Duration.ofMillis(500),
                Duration.ofMillis(150));
            default -> throw new IllegalArgumentException("Unsupported mode: " + mode);
        };
    }

    boolean runRemoteNormal() {
        return mode.equals("smoke") || mode.equals("remote") || mode.equals("full");
    }

    boolean runRedisNormal() {
        return mode.equals("smoke") || mode.equals("redis") || mode.equals("full");
    }

    boolean runDegradation() {
        return mode.equals("degradation") || mode.equals("full");
    }

    private static void requirePositive(List<Integer> values, String name) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        for (Integer value : values) {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(name + " must contain only positive values");
            }
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String normalizeMode(String mode) {
        String normalized = mode == null ? "smoke" : mode.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? "smoke" : normalized;
    }
}
