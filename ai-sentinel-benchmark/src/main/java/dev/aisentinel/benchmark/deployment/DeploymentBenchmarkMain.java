package dev.aisentinel.benchmark.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aisentinel.autoconfigure.evaluation.RemoteEvaluationClient;
import dev.aisentinel.autoconfigure.web.RemoteEvaluationController;
import dev.aisentinel.benchmark.EnvironmentMetadata;
import dev.aisentinel.benchmark.EnvironmentMetadataCollector;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DeploymentBenchmarkMain {

    static final String API_KEY = "benchmark-remote-api-key";

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String REMOTE_BOUNDARY =
        "Includes RemoteEvaluationClient JSON serialization, loopback HTTP transport, servlet dispatch, "
            + "RemoteEvaluationController handling, LocalEvaluationExecutor/LocalEvaluationBridge execution, "
            + "response serialization, loopback HTTP transport, and client deserialization. "
            + "Excludes WAN/internet latency, TLS termination, load balancers, host application business logic, "
            + "and production observability/export pipelines.";
    private static final String REDIS_BOUNDARY =
        "Includes loopback remote evaluation plus identity trust baseline interaction with the configured backend. "
            + "For Redis-backed scenarios this includes Lettuce client calls, local Docker-hosted Redis transport, "
            + "Redis Lua execution, and fallback-to-local behavior when Redis is unavailable. "
            + "Excludes WAN/cloud Redis latency, external IAM/IdP calls, and production network infrastructure.";

    private DeploymentBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "smoke" : args[0];
        Path resultsDir = Path.of(System.getProperty(
            "aisentinel.benchmark.resultsDir",
            "ai-sentinel-benchmark/results")).resolve("deployment").resolve(stamp());
        DeploymentBenchmarkConfig config = DeploymentBenchmarkConfig.forMode(mode, resultsDir);

        List<DeploymentBenchmarkResult> results = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", config.mode());
        summary.put("dockerAvailable", isCommandAvailable("docker"));
        summary.put("redisDockerProvisioning", "docker-cli");
        summary.put("dotnetAvailable", isCommandAvailable("dotnet"));
        summary.put("dotnetVersion", commandOutput("dotnet", "--version"));
        summary.put("redisRuntimeVersion", "redis:7-alpine");
        summary.put("dockerVersion", commandOutput("docker", "version", "--format", "{{.Server.Version}}"));

        if (config.runRemoteNormal() || config.runDegradation() || config.runRedisNormal()) {
            runRemoteAndRedisBenchmarks(config, results);
        }

        Path output = DeploymentBenchmarkResultWriter.write(resultsDir, config.mode(), results, summary);
        System.out.println("Deployment benchmark results: " + output.toAbsolutePath());
        System.out.println("NOTE: These are local deployment measurements, not production SLAs or detection claims.");
    }

    private static void runRemoteAndRedisBenchmarks(DeploymentBenchmarkConfig config,
                                                    List<DeploymentBenchmarkResult> results) throws Exception {
        if (config.runRemoteNormal() || config.runDegradation()) {
            runRemoteScenarios(config, results);
        }
        if (config.runRedisNormal() || config.runDegradation()) {
            runRedisScenarios(config, results);
        }
    }

    private static void runRemoteScenarios(DeploymentBenchmarkConfig config,
                                           List<DeploymentBenchmarkResult> results) throws Exception {
        try (RemoteBenchmarkServer server = RemoteBenchmarkServer.remoteOnly()) {
            server.start();
            for (Integer concurrency : config.remoteConcurrencyLevels()) {
                BenchmarkSentinelMetrics clientMetrics = new BenchmarkSentinelMetrics();
                RemoteEvaluationClient client = newClient(server.baseUrl(), config, clientMetrics);
                LoadHarness.RunResult run = LoadHarness.run(
                    concurrency,
                    config.warmupAttemptsPerThread(),
                    config.measuredAttemptsPerThread(),
                    () -> {
                        server.faults().reset();
                        server.metrics().reset();
                        clientMetrics.reset();
                    },
                    () -> {
                        server.faults().reset();
                        server.metrics().reset();
                        clientMetrics.reset();
                    },
                    (workerId, opIndex) -> invokeRemote(client, clientMetrics, requestFor(workerId, opIndex)));
                results.add(buildResult(
                    "deployment-benchmark",
                    "JAVA_REMOTE_NORMAL",
                    "remote-http-loopback",
                    "local-memory",
                    "statistical",
                    concurrency,
                    config,
                    REMOTE_BOUNDARY,
                    List.of(
                        "client serialization",
                        "loopback HTTP transport",
                        "server deserialization",
                        "local evaluation executor",
                        "client deserialization"),
                    List.of(
                        "WAN latency",
                        "TLS termination",
                        "business endpoint work",
                        "production telemetry export"),
                    run,
                    commandOutput("docker", "version", "--format", "{{.Server.Version}}"),
                    null,
                    null,
                    null,
                    Map.of()));
            }

            if (!config.runDegradation()) {
                return;
            }

            BenchmarkSentinelMetrics unavailableMetrics = new BenchmarkSentinelMetrics();
            int deadPort = reserveUnusedPort();
            RemoteEvaluationClient unavailableClient =
                newClient("http://127.0.0.1:" + deadPort, config, unavailableMetrics);
            LoadHarness.RunResult unavailable = LoadHarness.run(
                1,
                0,
                config.measuredAttemptsPerThread(),
                unavailableMetrics::reset,
                unavailableMetrics::reset,
                (workerId, opIndex) -> invokeRemote(unavailableClient, unavailableMetrics, requestFor(workerId, opIndex)));
            results.add(buildResult(
                "failure-recovery-test",
                "REMOTE_UNAVAILABLE",
                "remote-http-loopback",
                "local-memory",
                "statistical",
                1,
                config,
                REMOTE_BOUNDARY,
                List.of("client serialization", "connection attempt", "fail-open response construction"),
                List.of("server execution", "WAN latency", "TLS termination"),
                unavailable,
                commandOutput("docker", "version", "--format", "{{.Server.Version}}"),
                null,
                null,
                "No server was bound on the target port; client remained fail-open.",
                Map.of()));

            BenchmarkSentinelMetrics timeoutMetrics = new BenchmarkSentinelMetrics();
            RemoteEvaluationClient timeoutClient = newClient(server.baseUrl(), config, timeoutMetrics);
            LoadHarness.RunResult timeout = LoadHarness.run(
                1,
                0,
                config.measuredAttemptsPerThread(),
                () -> {
                    server.faults().delay(config.remoteReadTimeout().toMillis() * 3);
                    server.metrics().reset();
                    timeoutMetrics.reset();
                },
                () -> {
                    server.faults().delay(config.remoteReadTimeout().toMillis() * 3);
                    server.metrics().reset();
                    timeoutMetrics.reset();
                },
                (workerId, opIndex) -> invokeRemote(timeoutClient, timeoutMetrics, requestFor(workerId, opIndex)));
            results.add(buildResult(
                "failure-recovery-test",
                "REMOTE_SLOW_OR_TIMEOUT",
                "remote-http-loopback",
                "local-memory",
                "statistical",
                1,
                config,
                REMOTE_BOUNDARY,
                List.of("client timeout budget", "delayed server request handling", "fail-open response"),
                List.of("WAN latency", "TLS termination"),
                timeout,
                commandOutput("docker", "version", "--format", "{{.Server.Version}}"),
                null,
                null,
                "Benchmark filter delayed requests beyond client read timeout.",
                Map.of()));

            BenchmarkSentinelMetrics malformedMetrics = new BenchmarkSentinelMetrics();
            RemoteEvaluationClient malformedClient = newClient(server.baseUrl(), config, malformedMetrics);
            LoadHarness.RunResult malformed = LoadHarness.run(
                1,
                0,
                config.measuredAttemptsPerThread(),
                () -> {
                    server.faults().malformedResponse();
                    server.metrics().reset();
                    malformedMetrics.reset();
                },
                () -> {
                    server.faults().malformedResponse();
                    server.metrics().reset();
                    malformedMetrics.reset();
                },
                (workerId, opIndex) -> invokeRemote(malformedClient, malformedMetrics, requestFor(workerId, opIndex)));
            results.add(buildResult(
                "failure-recovery-test",
                "REMOTE_MALFORMED_RESPONSE",
                "remote-http-loopback",
                "local-memory",
                "statistical",
                1,
                config,
                REMOTE_BOUNDARY,
                List.of("client serialization", "HTTP round trip", "malformed JSON response parsing"),
                List.of("controller business evaluation result"),
                malformed,
                commandOutput("docker", "version", "--format", "{{.Server.Version}}"),
                null,
                null,
                "Benchmark fault filter returned malformed JSON to exercise client contract safety.",
                Map.of()));

            results.add(runRemoteRecoveryScenario(config, server));
        }
    }

    private static DeploymentBenchmarkResult runRemoteRecoveryScenario(DeploymentBenchmarkConfig config,
                                                                       RemoteBenchmarkServer server) throws Exception {
        BenchmarkSentinelMetrics metrics = new BenchmarkSentinelMetrics();
        RemoteEvaluationClient client = newClient(server.baseUrl(), config, metrics);
        server.faults().reset();
        server.metrics().reset();
        metrics.reset();

        LoadHarness.RunResult healthyBefore = LoadHarness.run(
            1,
            0,
            Math.max(8, config.measuredAttemptsPerThread() / 3),
            () -> { },
            () -> { },
            (workerId, opIndex) -> invokeRemote(client, metrics, requestFor(workerId, opIndex)));

        server.stop();
        LoadHarness.RunResult failed = LoadHarness.run(
            1,
            0,
            Math.max(6, config.measuredAttemptsPerThread() / 4),
            metrics::reset,
            metrics::reset,
            (workerId, opIndex) -> invokeRemote(client, metrics, requestFor(workerId, opIndex)));

        server.start();
        LoadHarness.RunResult recovered = LoadHarness.run(
            1,
            0,
            Math.max(8, config.measuredAttemptsPerThread() / 3),
            () -> {
                server.faults().reset();
                server.metrics().reset();
                metrics.reset();
            },
            () -> {
                server.faults().reset();
                server.metrics().reset();
                metrics.reset();
            },
            (workerId, opIndex) -> invokeRemote(client, metrics, requestFor(workerId, opIndex + 10_000)));

        long attempts = healthyBefore.attempts() + failed.attempts() + recovered.attempts();
        long successes = healthyBefore.successes() + failed.successes() + recovered.successes();
        long failures = healthyBefore.failures() + failed.failures() + recovered.failures();
        long timeouts = healthyBefore.timeouts() + failed.timeouts() + recovered.timeouts();
        long fallbacks = healthyBefore.fallbacks() + failed.fallbacks() + recovered.fallbacks();
        long failOpen = healthyBefore.failOpenCount() + failed.failOpenCount() + recovered.failOpenCount();
        long unexpected = healthyBefore.unexpectedEnforcementCount()
            + failed.unexpectedEnforcementCount()
            + recovered.unexpectedEnforcementCount();
        long transportFailures = healthyBefore.transportFailures() + failed.transportFailures() + recovered.transportFailures();
        long malformed = healthyBefore.malformedResponses() + failed.malformedResponses() + recovered.malformedResponses();
        List<Long> successLatencies = new ArrayList<>();
        successLatencies.addAll(healthyBefore.successLatenciesNanos());
        successLatencies.addAll(recovered.successLatenciesNanos());
        List<Long> failureLatencies = new ArrayList<>(failed.failureLatenciesNanos());
        LoadHarness.RunResult aggregate = new LoadHarness.RunResult(
            attempts,
            successes,
            failures,
            timeouts,
            fallbacks,
            failOpen,
            unexpected,
            transportFailures,
            malformed,
            successLatencies,
            failureLatencies,
            healthyBefore.measuredElapsedNanos() + failed.measuredElapsedNanos() + recovered.measuredElapsedNanos());

        return buildResult(
            "failure-recovery-test",
            "REMOTE_RECOVERY",
            "remote-http-loopback",
            "local-memory",
            "statistical",
            1,
            config,
            REMOTE_BOUNDARY,
            List.of("healthy remote evaluation", "remote outage", "service restart", "post-recovery evaluation"),
            List.of("WAN latency", "TLS termination"),
            aggregate,
            commandOutput("docker", "version", "--format", "{{.Server.Version}}"),
            null,
            "healthy->unavailable->healthy with same client and same port",
            "Healthy pre-check and recovered post-check both returned successful remote evaluations.",
            Map.of());
    }

    private static void runRedisScenarios(DeploymentBenchmarkConfig config,
                                          List<DeploymentBenchmarkResult> results) throws Exception {
        try (RedisContainerSupport redis = new RedisContainerSupport()) {
            redis.start();

            try (RemoteBenchmarkServer localStateServer = RemoteBenchmarkServer.localTrust()) {
                localStateServer.start();
                results.addAll(runRedisNormalFamily(config, localStateServer, "TRUST_LOCAL_MEMORY_REFERENCE", "local-memory", null));
            }

            try (RemoteBenchmarkServer redisServer =
                     RemoteBenchmarkServer.redisBacked(redis.host(), redis.port(), config.redisCommandTimeout())) {
                redisServer.start();
                results.addAll(runRedisNormalFamily(config, redisServer, "REDIS_NORMAL", "redis", redis.redisVersion()));
                if (!config.runDegradation()) {
                    return;
                }
                results.add(runRedisUnavailableScenario(config, redis));
                results.add(runRedisInterruptedScenario(config, redisServer, redis));
                results.add(runRedisRecoveryScenario(config, redisServer, redis));
            }
        }
    }

    private static List<DeploymentBenchmarkResult> runRedisNormalFamily(DeploymentBenchmarkConfig config,
                                                                        RemoteBenchmarkServer server,
                                                                        String scenario,
                                                                        String backend,
                                                                        String redisVersion) throws Exception {
        List<DeploymentBenchmarkResult> results = new ArrayList<>();
        for (Integer concurrency : config.redisConcurrencyLevels()) {
            BenchmarkSentinelMetrics clientMetrics = new BenchmarkSentinelMetrics();
            RemoteEvaluationClient client = newClient(server.baseUrl(), config, clientMetrics);
            LoadHarness.RunResult run = LoadHarness.run(
                concurrency,
                config.warmupAttemptsPerThread(),
                config.measuredAttemptsPerThread(),
                () -> {
                    server.faults().reset();
                    server.metrics().reset();
                    clientMetrics.reset();
                },
                () -> {
                    server.faults().reset();
                    server.metrics().reset();
                    clientMetrics.reset();
                },
                (workerId, opIndex) -> invokeRemote(client, clientMetrics, trustRequestFor(workerId, opIndex)));
            results.add(buildResult(
                "deployment-benchmark",
                scenario,
                "remote-http-loopback",
                backend,
                "statistical+behavioral-trust",
                concurrency,
                config,
                REDIS_BOUNDARY,
                List.of(
                    "remote evaluation HTTP path",
                    "feature extraction",
                    "behavioral trust baseline update",
                    backend.equals("redis") ? "Docker Redis command path" : "local in-memory trust baseline"),
                List.of(
                    "WAN latency",
                    "TLS termination",
                    "external identity providers",
                    "production Redis network topology"),
                run,
                commandOutput("docker", "version", "--format", "{{.Server.Version}}"),
                redisVersion,
                null,
                null,
                redisExtraCounts(server.metrics())));
        }
        return results;
    }

    private static DeploymentBenchmarkResult runRedisUnavailableScenario(DeploymentBenchmarkConfig config,
                                                                         RedisContainerSupport redis) throws Exception {
        String host = redis.host();
        int port = redis.port();
        redis.stopContainer();
        try (RemoteBenchmarkServer server = RemoteBenchmarkServer.redisBacked(host, port, config.redisCommandTimeout())) {
            server.start();
            BenchmarkSentinelMetrics clientMetrics = new BenchmarkSentinelMetrics();
            RemoteEvaluationClient client = newClient(server.baseUrl(), config, clientMetrics);
            LoadHarness.RunResult run = LoadHarness.run(
                1,
                0,
                config.measuredAttemptsPerThread(),
                () -> {
                    server.metrics().reset();
                    clientMetrics.reset();
                },
                () -> {
                    server.metrics().reset();
                    clientMetrics.reset();
                },
                (workerId, opIndex) -> invokeRemote(client, clientMetrics, trustRequestFor(workerId, opIndex)));
            return buildResult(
                "failure-recovery-test",
                "REDIS_UNAVAILABLE",
                "remote-http-loopback",
                "redis",
                "statistical+behavioral-trust",
                1,
                config,
                REDIS_BOUNDARY,
                List.of("remote evaluation HTTP path", "Redis-backed trust baseline attempt", "local fallback"),
                List.of("production Redis topology", "WAN latency"),
                run,
                commandOutput("docker", "version", "--format", "{{.Server.Version}}"),
                redis.redisVersion(),
                null,
                "Server started against a stopped Redis endpoint; trust baseline fell back to local memory.",
                redisExtraCounts(server.metrics()));
        } finally {
            redis.start();
        }
    }

    private static DeploymentBenchmarkResult runRedisInterruptedScenario(DeploymentBenchmarkConfig config,
                                                                         RemoteBenchmarkServer server,
                                                                         RedisContainerSupport redis) throws Exception {
        BenchmarkSentinelMetrics clientMetrics = new BenchmarkSentinelMetrics();
        RemoteEvaluationClient client = newClient(server.baseUrl(), config, clientMetrics);
        redis.pause();
        try {
            LoadHarness.RunResult run = LoadHarness.run(
                1,
                0,
                config.measuredAttemptsPerThread(),
                () -> {
                    server.metrics().reset();
                    clientMetrics.reset();
                },
                () -> {
                    server.metrics().reset();
                    clientMetrics.reset();
                },
                (workerId, opIndex) -> invokeRemote(client, clientMetrics, trustRequestFor(workerId, opIndex + 20_000)));
            return buildResult(
                "failure-recovery-test",
                "REDIS_INTERRUPTED",
                "remote-http-loopback",
                "redis",
                "statistical+behavioral-trust",
                1,
                config,
                REDIS_BOUNDARY,
                List.of("remote evaluation HTTP path", "paused Docker Redis", "local trust fallback"),
                List.of("production Redis network topology"),
                run,
                commandOutput("docker", "version", "--format", "{{.Server.Version}}"),
                redis.redisVersion(),
                null,
                "Redis container was paused during measured requests.",
                redisExtraCounts(server.metrics()));
        } finally {
            redis.unpause();
        }
    }

    private static DeploymentBenchmarkResult runRedisRecoveryScenario(DeploymentBenchmarkConfig config,
                                                                      RemoteBenchmarkServer server,
                                                                      RedisContainerSupport redis) throws Exception {
        BenchmarkSentinelMetrics clientMetrics = new BenchmarkSentinelMetrics();
        RemoteEvaluationClient client = newClient(server.baseUrl(), config, clientMetrics);

        LoadHarness.RunResult healthyBefore = LoadHarness.run(
            1,
            0,
            Math.max(8, config.measuredAttemptsPerThread() / 3),
            () -> {
                server.metrics().reset();
                clientMetrics.reset();
            },
            () -> {
                server.metrics().reset();
                clientMetrics.reset();
            },
            (workerId, opIndex) -> invokeRemote(client, clientMetrics, trustRequestFor(workerId, opIndex)));
        Map<String, Object> healthyCounts = redisExtraCounts(server.metrics());

        redis.pause();
        LoadHarness.RunResult interrupted = LoadHarness.run(
            1,
            0,
            Math.max(6, config.measuredAttemptsPerThread() / 4),
            () -> {
                server.metrics().reset();
                clientMetrics.reset();
            },
            () -> {
                server.metrics().reset();
                clientMetrics.reset();
            },
            (workerId, opIndex) -> invokeRemote(client, clientMetrics, trustRequestFor(workerId, opIndex + 40_000)));
        Map<String, Object> interruptedCounts = redisExtraCounts(server.metrics());
        redis.unpause();

        LoadHarness.RunResult recovered = LoadHarness.run(
            1,
            0,
            Math.max(8, config.measuredAttemptsPerThread() / 3),
            () -> {
                server.metrics().reset();
                clientMetrics.reset();
            },
            () -> {
                server.metrics().reset();
                clientMetrics.reset();
            },
            (workerId, opIndex) -> invokeRemote(client, clientMetrics, trustRequestFor(workerId, opIndex + 50_000)));
        Map<String, Object> recoveredCounts = redisExtraCounts(server.metrics());

        long attempts = healthyBefore.attempts() + interrupted.attempts() + recovered.attempts();
        long successes = healthyBefore.successes() + interrupted.successes() + recovered.successes();
        long failures = healthyBefore.failures() + interrupted.failures() + recovered.failures();
        long timeouts = healthyBefore.timeouts() + interrupted.timeouts() + recovered.timeouts();
        long fallbacks = healthyBefore.fallbacks() + interrupted.fallbacks() + recovered.fallbacks();
        long failOpen = healthyBefore.failOpenCount() + interrupted.failOpenCount() + recovered.failOpenCount();
        long unexpected = healthyBefore.unexpectedEnforcementCount()
            + interrupted.unexpectedEnforcementCount()
            + recovered.unexpectedEnforcementCount();
        long transportFailures = healthyBefore.transportFailures()
            + interrupted.transportFailures()
            + recovered.transportFailures();
        long malformed = healthyBefore.malformedResponses()
            + interrupted.malformedResponses()
            + recovered.malformedResponses();
        List<Long> successLatencies = new ArrayList<>();
        successLatencies.addAll(healthyBefore.successLatenciesNanos());
        successLatencies.addAll(interrupted.successLatenciesNanos());
        successLatencies.addAll(recovered.successLatenciesNanos());
        List<Long> failureLatencies = new ArrayList<>();
        LoadHarness.RunResult aggregate = new LoadHarness.RunResult(
            attempts,
            successes,
            failures,
            timeouts,
            fallbacks,
            failOpen,
            unexpected,
            transportFailures,
            malformed,
            successLatencies,
            failureLatencies,
            healthyBefore.measuredElapsedNanos() + interrupted.measuredElapsedNanos() + recovered.measuredElapsedNanos());

        return buildResult(
            "failure-recovery-test",
            "REDIS_RECOVERY",
            "remote-http-loopback",
            "redis",
            "statistical+behavioral-trust",
            1,
            config,
            REDIS_BOUNDARY,
            List.of("healthy Redis-backed trust", "paused Redis interruption", "unpaused Redis recovery"),
            List.of("production Redis topology"),
            aggregate,
            commandOutput("docker", "version", "--format", "{{.Server.Version}}"),
            redis.redisVersion(),
            "healthy->paused fallback->healthy",
            "Responses remained successful during interruption because Redis baseline failures fall back to local memory.",
            sumRedisCounts(healthyCounts, interruptedCounts, recoveredCounts));
    }

    private static DeploymentBenchmarkResult buildResult(String benchmarkType,
                                                         String scenario,
                                                         String deploymentMode,
                                                         String stateBackend,
                                                         String scorer,
                                                         Integer concurrency,
                                                         DeploymentBenchmarkConfig config,
                                                         String measurementBoundary,
                                                         List<String> includes,
                                                         List<String> excludes,
                                                         LoadHarness.RunResult run,
                                                         String dockerVersion,
                                                         String redisVersion,
                                                         String recoveryOutcome,
                                                         String notes,
                                                         Map<String, Object> extraCounts) {
        EnvironmentMetadata base = EnvironmentMetadataCollector.collect();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("attempts", run.attempts());
        counts.put("successes", run.successes());
        counts.put("failures", run.failures());
        counts.put("timeouts", run.timeouts());
        counts.put("fallbacks", run.fallbacks());
        counts.put("failOpenCount", run.failOpenCount());
        counts.put("unexpectedEnforcementCount", run.unexpectedEnforcementCount());
        counts.put("transportFailures", run.transportFailures());
        counts.put("malformedResponses", run.malformedResponses());
        if (extraCounts != null && !extraCounts.isEmpty()) {
            counts.putAll(extraCounts);
        }
        double elapsedSeconds = run.measuredElapsedNanos() / 1_000_000_000.0;
        Double throughput = elapsedSeconds <= 0.0 ? null : run.attempts() / elapsedSeconds;
        return new DeploymentBenchmarkResult(
            DeploymentBenchmarkVersions.SCHEMA_VERSION,
            DeploymentBenchmarkVersions.HARNESS_VERSION,
            DeploymentBenchmarkVersions.SUITE_NAME,
            benchmarkType,
            Instant.now().toString(),
            base.sentinelVersion(),
            base.gitCommit(),
            scenario,
            deploymentMode,
            stateBackend,
            scorer,
            base.featureSchemaVersion(),
            base.javaVersion(),
            commandOutput("dotnet", "--version"),
            redisVersion,
            dockerVersion,
            base.osName() + " " + base.osVersion(),
            base.osArch(),
            concurrency,
            config.warmupAttemptsPerThread(),
            config.measuredAttemptsPerThread(),
            scenario.startsWith("REDIS") ? config.redisCommandTimeout().toMillis() : config.remoteReadTimeout().toMillis(),
            measurementBoundary,
            includes,
            excludes,
            counts,
            LatencyStats.fromNanos(run.successLatenciesNanos()),
            LatencyStats.fromNanos(run.failureLatenciesNanos()),
            throughput,
            recoveryOutcome,
            notes);
    }

    private static Map<String, Object> redisExtraCounts(BenchmarkSentinelMetrics serverMetrics) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("redisFailures", serverMetrics.trustRedisFailure());
        counts.put("fallbacks", serverMetrics.trustRedisFallback());
        counts.put("serverFailOpenCount", serverMetrics.failOpenCount());
        return counts;
    }

    private static Map<String, Object> sumRedisCounts(Map<String, Object>... parts) {
        long redisFailures = 0L;
        long fallbacks = 0L;
        long serverFailOpenCount = 0L;
        for (Map<String, Object> part : parts) {
            if (part == null) {
                continue;
            }
            redisFailures += number(part.get("redisFailures"));
            fallbacks += number(part.get("fallbacks"));
            serverFailOpenCount += number(part.get("serverFailOpenCount"));
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("redisFailures", redisFailures);
        counts.put("fallbacks", fallbacks);
        counts.put("serverFailOpenCount", serverFailOpenCount);
        return counts;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static LoadHarness.OperationResult invokeRemote(RemoteEvaluationClient client,
                                                            BenchmarkSentinelMetrics metrics,
                                                            EvaluationRequest request) {
        long start = System.nanoTime();
        EvaluationResponse response = client.evaluate(request);
        long elapsed = System.nanoTime() - start;
        String outcome = metrics.consumeLastRemoteOutcome();
        boolean remoteFailure = response.evaluationStatuses().contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
        boolean success = !remoteFailure && "SUCCESS".equals(outcome);
        boolean timeout = "TIMEOUT".equals(outcome);
        boolean malformed = "MALFORMED_RESPONSE".equals(outcome)
            || "VERSION_MISMATCH".equals(outcome)
            || "CORRELATION_MISMATCH".equals(outcome);
        boolean transportFailure = remoteFailure && !malformed;
        boolean unexpectedEnforcement = remoteFailure
            && (response.action() != EnforcementAction.ALLOW
            || !response.proceed()
            || response.anomalyScore() != null
            || response.policyScore() != null
            || !response.factors().isEmpty());
        return new LoadHarness.OperationResult(
            success,
            timeout,
            false,
            remoteFailure,
            unexpectedEnforcement,
            transportFailure,
            malformed,
            elapsed);
    }

    private static EvaluationRequest requestFor(int workerId, int opIndex) {
        String identity = "remote-user-" + ((workerId * 257) + opIndex % 32);
        return EvaluationRequest.builder()
            .correlationId("remote-" + workerId + "-" + opIndex)
            .method("GET")
            .path("/api/benchmark/remote")
            .identityKey(identity)
            .remoteAddress("127.0.0.1")
            .headers(Map.of("user-agent", "ai-sentinel-remote-benchmark"))
            .parameters(Map.of("slot", Integer.toString(opIndex % 4)))
            .build();
    }

    private static EvaluationRequest trustRequestFor(int workerId, int opIndex) {
        String identity = "trust-user-" + ((workerId * 257) + opIndex % 48);
        return EvaluationRequest.builder()
            .correlationId("redis-" + workerId + "-" + opIndex)
            .method("GET")
            .path("/api/benchmark/trust")
            .identityKey(identity)
            .remoteAddress("127.0.0.1")
            .headers(Map.of(
                "user-agent", "ai-sentinel-redis-benchmark",
                "x-benchmark-op", Integer.toString(opIndex % 8)))
            .build();
    }

    private static RemoteEvaluationClient newClient(String baseUrl,
                                                    DeploymentBenchmarkConfig config,
                                                    BenchmarkSentinelMetrics metrics) {
        return new RemoteEvaluationClient(
            baseUrl,
            RemoteEvaluationController.PATH,
            API_KEY,
            config.remoteConnectTimeout(),
            config.remoteReadTimeout(),
            JSON,
            metrics);
    }

    private static String stamp() {
        return Instant.now().toString().replace(":", "").replace(".", "-");
    }

    private static String commandOutput(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String output = reader.lines().reduce("", (a, b) -> a + (a.isEmpty() ? "" : "\n") + b).trim();
                int exit = process.waitFor();
                return exit == 0 ? output : null;
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isCommandAvailable(String command) {
        return commandOutput("sh", "-c", "command -v " + command) != null;
    }

    private static int reserveUnusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }
}
