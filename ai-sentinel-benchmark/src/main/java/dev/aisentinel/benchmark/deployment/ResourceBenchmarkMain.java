package dev.aisentinel.benchmark.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aisentinel.autoconfigure.evaluation.RemoteEvaluationClient;
import dev.aisentinel.autoconfigure.web.RemoteEvaluationController;
import dev.aisentinel.benchmark.EnvironmentMetadata;
import dev.aisentinel.benchmark.EnvironmentMetadataCollector;
import dev.aisentinel.benchmark.fixture.BenchmarkHttpRequestView;
import dev.aisentinel.benchmark.fixture.BenchmarkPipelineFactory;
import dev.aisentinel.benchmark.fixture.NoopEnforcementResponse;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ResourceBenchmarkMain {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private ResourceBenchmarkMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "smoke" : args[0];
        Path resultsDir = Path.of(System.getProperty("aisentinel.benchmark.resultsDir", "ai-sentinel-benchmark/results"))
            .resolve("resources")
            .resolve(stamp());
        ResourceBenchmarkConfig config = ResourceBenchmarkConfig.forMode(mode, resultsDir);
        EnvironmentMetadata base = EnvironmentMetadataCollector.collect();

        List<ResourceBenchmarkResult> results = new ArrayList<>();
        if (config.runInProcess()) {
            runInProcess(config, base, results);
        }
        if (config.runRemote()) {
            runRemote(config, base, results);
        }
        if (config.runRedis()) {
            runRedis(config, base, results);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("mode", config.mode());
        summary.put("samplingIntervalMillis", config.samplingInterval().toMillis());
        summary.put("cpuInterpretation",
            "processCpuCoresEquivalent = processCpuTimeDeltaNanos / measuredWallNanos; "
                + "processCpuPercentOfMachine = processCpuCoresEquivalent / logicalProcessors * 100");
        summary.put("heapSource", "MemoryMXBean heap usage snapshots + sampled measured-window peak");
        summary.put("rssSource", "best-effort host process RSS via ps -o rss on current pid");
        summary.put("gcSource", "GarbageCollectorMXBean collection count/time deltas over measured window");
        summary.put("redisStatsSource", "docker stats --no-stream sampled during measured window when Redis-backed");
        summary.put("limitations", List.of(
            "RESOURCE BENCHMARK RESULT != PRODUCTION CAPACITY",
            "LOCAL RESOURCE RESULT != CLOUD SIZING GUIDANCE",
            "PROCESS RSS != JVM HEAP",
            "ALLOCATION RATE != MEMORY LEAK",
            "JMH allocation/GC profiling is a separate opt-in pass"));

        Path output = ResourceBenchmarkResultWriter.write(resultsDir, config.mode(), results, summary);
        System.out.println("Resource benchmark results: " + output.toAbsolutePath());
    }

    private static void runInProcess(ResourceBenchmarkConfig config,
                                     EnvironmentMetadata base,
                                     List<ResourceBenchmarkResult> results) throws Exception {
        for (Integer concurrency : config.inProcessConcurrency()) {
            BenchmarkPipelineFactory.AssembledPipeline pipeline = BenchmarkPipelineFactory.statisticalPipeline();
            BenchmarkHttpRequestView[] requests = workerRequests(concurrency, false);
            ResourceLoadHarness.Operation op =
                (workerId, sequence) -> pipeline.pipeline().process(
                    requests[workerId],
                    NoopEnforcementResponse.INSTANCE,
                    "resource-user-" + (workerId % 32));
            results.add(measureScenario(
                base,
                "RESOURCE_IN_PROCESS",
                "in-process",
                "local-memory",
                "statistical",
                concurrency,
                config,
                null,
                null,
                "Includes benchmark-host JVM pipeline execution only; excludes transport, Redis, and profiler overhead.",
                "No latency baseline comparison or production capacity claim should be inferred.",
                op));
        }
    }

    private static void runRemote(ResourceBenchmarkConfig config,
                                  EnvironmentMetadata base,
                                  List<ResourceBenchmarkResult> results) throws Exception {
        try (RemoteBenchmarkServer server = RemoteBenchmarkServer.remoteOnly()) {
            server.start();
            for (Integer concurrency : config.remoteConcurrency()) {
                BenchmarkSentinelMetrics metrics = new BenchmarkSentinelMetrics();
                RemoteEvaluationClient client = new RemoteEvaluationClient(
                    server.baseUrl(),
                    RemoteEvaluationController.PATH,
                    DeploymentBenchmarkMain.API_KEY,
                    DeploymentBenchmarkConfig.forMode("smoke", config.resultsDir()).remoteConnectTimeout(),
                    DeploymentBenchmarkConfig.forMode("smoke", config.resultsDir()).remoteReadTimeout(),
                    JSON,
                    metrics);
                ResourceLoadHarness.Operation op =
                    (workerId, sequence) -> invokeRemote(client, requestFor(workerId, sequence));
                results.add(measureScenario(
                    base,
                    "RESOURCE_REMOTE",
                    "remote-http-loopback",
                    "local-memory",
                    "statistical",
                    concurrency,
                    config,
                    null,
                    null,
                    "Combined benchmark-client plus local evaluator in one JVM over loopback HTTP; excludes WAN/TLS/load balancers.",
                    "Client and server share one JVM in this benchmark harness.",
                    op));
            }
        }
    }

    private static void runRedis(ResourceBenchmarkConfig config,
                                 EnvironmentMetadata base,
                                 List<ResourceBenchmarkResult> results) throws Exception {
        try (RemoteBenchmarkServer localTrust = RemoteBenchmarkServer.localTrust()) {
            localTrust.start();
            for (Integer concurrency : config.redisConcurrency()) {
                BenchmarkSentinelMetrics metrics = new BenchmarkSentinelMetrics();
                RemoteEvaluationClient client = new RemoteEvaluationClient(
                    localTrust.baseUrl(),
                    RemoteEvaluationController.PATH,
                    DeploymentBenchmarkMain.API_KEY,
                    DeploymentBenchmarkConfig.forMode("smoke", config.resultsDir()).remoteConnectTimeout(),
                    DeploymentBenchmarkConfig.forMode("smoke", config.resultsDir()).remoteReadTimeout(),
                    JSON,
                    metrics);
                results.add(measureScenario(
                    base,
                    "RESOURCE_REDIS_LOCAL_MEMORY_CONTROL",
                    "remote-http-loopback",
                    "local-memory",
                    "statistical",
                    concurrency,
                    config,
                    null,
                    localTrust.metrics(),
                    "Combined benchmark-client plus local evaluator with in-JVM trust baseline state; excludes Redis and WAN infrastructure.",
                    "Control scenario for Redis-backed comparison.",
                    (workerId, sequence) -> invokeRemote(client, trustRequestFor(workerId, sequence))));
            }
        }
        try (RedisContainerSupport redis = new RedisContainerSupport()) {
            redis.start();
            try (RemoteBenchmarkServer server =
                     RemoteBenchmarkServer.redisBacked(redis.host(), redis.port(), Duration.ofMillis(250))) {
                server.start();
                for (Integer concurrency : config.redisConcurrency()) {
                    BenchmarkSentinelMetrics metrics = new BenchmarkSentinelMetrics();
                    RemoteEvaluationClient client = new RemoteEvaluationClient(
                        server.baseUrl(),
                        RemoteEvaluationController.PATH,
                        DeploymentBenchmarkMain.API_KEY,
                        DeploymentBenchmarkConfig.forMode("smoke", config.resultsDir()).remoteConnectTimeout(),
                        DeploymentBenchmarkConfig.forMode("smoke", config.resultsDir()).remoteReadTimeout(),
                        JSON,
                        metrics);
                    results.add(measureScenario(
                        base,
                        "RESOURCE_REDIS_BACKED",
                        "remote-http-loopback",
                        "redis",
                        "statistical",
                        concurrency,
                        config,
                        redis.containerId(),
                        server.metrics(),
                        "Combined benchmark-client plus local evaluator JVM plus Docker-hosted local Redis; excludes managed/cloud Redis and WAN infrastructure.",
                        "Redis container metrics are local Docker samples, not production Redis sizing guidance.",
                        (workerId, sequence) -> invokeRemote(client, trustRequestFor(workerId, sequence))));
                }
            }
        }
    }

    private static ResourceBenchmarkResult measureScenario(EnvironmentMetadata base,
                                                           String scenario,
                                                           String deploymentMode,
                                                           String stateBackend,
                                                           String modelState,
                                                           Integer concurrency,
                                                           ResourceBenchmarkConfig config,
                                                           String redisContainerId,
                                                           BenchmarkSentinelMetrics scenarioMetrics,
                                                           String boundary,
                                                           String limitations,
                                                           ResourceLoadHarness.Operation operation) throws Exception {
        long heapBefore = ResourceSupport.heapUsedBytes();
        ResourceLoadHarness.warmup(concurrency, config.warmupDuration(), operation);
        if (scenarioMetrics != null) {
            scenarioMetrics.reset();
        }
        long heapAfterWarmup = ResourceSupport.heapUsedBytes();
        ResourceLoadHarness.RunResult run;
        Long peakHeap;
        Long peakRss;
        Double redisCpu;
        Long redisMem;
        try (ResourceSampler sampler = new ResourceSampler(config.samplingInterval(), redisContainerId)) {
            sampler.start();
            ResourceSupport.CpuSnapshot cpuStart = ResourceSupport.cpuSnapshot();
            ResourceSupport.GcSnapshot gcStart = ResourceSupport.gcSnapshot();
            run = ResourceLoadHarness.measure(concurrency, config.measurementDuration(), operation);
            ResourceSupport.CpuSnapshot cpuEnd = ResourceSupport.cpuSnapshot();
            ResourceSupport.GcSnapshot gcEnd = ResourceSupport.gcSnapshot();
            peakHeap = sampler.peakHeapBytes();
            peakRss = sampler.peakRssBytes();
            redisCpu = sampler.averageRedisCpuPercent();
            redisMem = sampler.latestRedisMemoryBytes();
            Long cpuDelta = delta(cpuStart.processCpuTimeNanos(), cpuEnd.processCpuTimeNanos());
            Long gcCountDelta = delta(gcStart.collectionCount(), gcEnd.collectionCount());
            Long gcTimeDelta = delta(gcStart.collectionTimeMillis(), gcEnd.collectionTimeMillis());
            Long redisTrustSuccesses = scenarioMetrics == null ? null : scenarioMetrics.trustRedisSuccess();
            Long redisTrustFailures = scenarioMetrics == null ? null : scenarioMetrics.trustRedisFailure();
            Long redisTrustFallbacks = scenarioMetrics == null ? null : scenarioMetrics.trustRedisFallback();
            if ("redis".equals(stateBackend) && (redisTrustSuccesses == null || redisTrustSuccesses == 0L)) {
                throw new IllegalStateException("Redis-backed resource scenario did not record Redis trust baseline success");
            }
            long measuredWallNanos = run.measuredWallNanos();
            long heapAfter = ResourceSupport.heapUsedBytes();
            Long rssAfter = ResourceSupport.processRssBytes();
            double throughput = run.attempts() == 0L
                ? 0.0
                : run.attempts() / (measuredWallNanos / 1_000_000_000.0);
            return new ResourceBenchmarkResult(
                ResourceBenchmarkVersions.SCHEMA_VERSION,
                ResourceBenchmarkVersions.HARNESS_VERSION,
                base.sentinelVersion(),
                base.gitCommit(),
                ResourceSupport.dirtyTree(),
                Instant.now().toString(),
                scenario,
                deploymentMode,
                stateBackend,
                modelState,
                concurrency,
                config.warmupDuration().toMillis(),
                config.measurementDuration().toMillis(),
                run.attempts(),
                run.successes(),
                run.failures(),
                throughput,
                base.javaVersion(),
                base.osName() + " " + base.osVersion(),
                base.osArch(),
                base.availableProcessors(),
                heapBefore,
                heapAfterWarmup,
                peakHeap,
                heapAfter,
                ResourceSupport.heapCommittedBytes(),
                ResourceSupport.heapMaxBytes(),
                cpuDelta,
                ResourceSupport.processCpuCoresEquivalent(cpuDelta, measuredWallNanos),
                ResourceSupport.processCpuPercentOfMachine(cpuDelta, measuredWallNanos,
                    base.availableProcessors() != null ? base.availableProcessors() : 0),
                rssAfter,
                peakRss,
                gcCountDelta,
                gcTimeDelta,
                null,
                null,
                redisCpu,
                redisMem,
                redisTrustSuccesses,
                redisTrustFailures,
                redisTrustFallbacks,
                boundary,
                limitations);
        }
    }

    private static boolean invokeRemote(RemoteEvaluationClient client, EvaluationRequest request) {
        EvaluationResponse response = client.evaluate(request);
        return !response.evaluationStatuses().contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name())
            && response.action() != EnforcementAction.BLOCK
            && response.proceed();
    }

    private static EvaluationRequest requestFor(int workerId, long sequence) {
        String identity = "remote-user-" + ((workerId * 257L) + sequence % 32L);
        return EvaluationRequest.builder()
            .correlationId("resource-remote-" + workerId + "-" + sequence)
            .method("GET")
            .path("/api/benchmark/remote")
            .identityKey(identity)
            .remoteAddress("127.0.0.1")
            .headers(Map.of("user-agent", "ai-sentinel-resource-benchmark"))
            .parameters(Map.of("slot", Long.toString(sequence % 4L)))
            .build();
    }

    private static EvaluationRequest trustRequestFor(int workerId, long sequence) {
        String identity = "trust-user-" + ((workerId * 257L) + sequence % 48L);
        return EvaluationRequest.builder()
            .correlationId("resource-redis-" + workerId + "-" + sequence)
            .method("GET")
            .path("/api/benchmark/trust")
            .identityKey(identity)
            .remoteAddress("127.0.0.1")
            .headers(Map.of(
                "user-agent", "ai-sentinel-resource-benchmark",
                "x-benchmark-op", Long.toString(sequence % 8L)))
            .build();
    }

    private static BenchmarkHttpRequestView[] workerRequests(int concurrency, boolean larger) {
        BenchmarkHttpRequestView[] views = new BenchmarkHttpRequestView[concurrency];
        for (int i = 0; i < concurrency; i++) {
            views[i] = larger ? BenchmarkHttpRequestView.largerValid() : BenchmarkHttpRequestView.typical();
        }
        return views;
    }

    private static Long delta(Long start, Long end) {
        if (start == null || end == null) {
            return null;
        }
        if (end < start) {
            return null;
        }
        return end - start;
    }

    private static String stamp() {
        return Instant.now().toString().replace(":", "").replace(".", "-");
    }
}
