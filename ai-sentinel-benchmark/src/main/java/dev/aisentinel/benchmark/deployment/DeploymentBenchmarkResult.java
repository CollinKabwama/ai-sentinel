package dev.aisentinel.benchmark.deployment;

import java.util.List;
import java.util.Map;

record DeploymentBenchmarkResult(
    String resultSchemaVersion,
    String harnessVersion,
    String suiteName,
    String benchmarkType,
    String capturedAtUtc,
    String sentinelVersion,
    String gitCommit,
    String scenario,
    String deploymentMode,
    String stateBackend,
    String scorer,
    String featureSchemaVersion,
    String javaVersion,
    String dotnetVersion,
    String redisVersion,
    String dockerVersion,
    String os,
    String architecture,
    Integer concurrency,
    Integer warmupAttemptsPerThread,
    Integer measuredAttemptsPerThread,
    Long timeoutMillis,
    String measurementBoundary,
    List<String> includes,
    List<String> excludes,
    Map<String, Object> counts,
    LatencyStats successLatency,
    LatencyStats failureLatency,
    Double throughputRequestsPerSecond,
    String recoveryOutcome,
    String notes
) {
}
