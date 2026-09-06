package dev.aisentinel.benchmark.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ComparisonAdapters {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private ComparisonAdapters() {
    }

    static NormalizedBenchmarkSet loadReferenceBaseline(Path path) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(path));
        EnvironmentFingerprint env = new EnvironmentFingerprint(
            text(root.path("environment"), "os"),
            text(root.path("environment"), "architecture"),
            text(root.path("environment"), "javaVersion"),
            integer(root.path("environment"), "logicalCpus"),
            longValue(root.path("environment"), "maxHeapBytes"),
            text(root.path("environment"), "featureSchemaVersion"),
            text(root.path("environment"), "deploymentMode"),
            text(root.path("environment"), "stateBackend"),
            text(root.path("environment"), "suiteName"),
            text(root, "environmentId"));
        List<NormalizedMetric> metrics = new ArrayList<>();
        for (JsonNode row : root.path("results")) {
            Map<String, String> params = params(row.path("params"));
            String benchmarkId = text(row, "fullBenchmark");
            String unit = text(row, "unit");
            Integer concurrency = benchmarkId.contains("FourThreads") ? 4
                : benchmarkId.contains("SixteenThreads") ? 16
                : benchmarkId.contains("OneThread") ? 1
                : 1;
            String primary = jmhPrimaryMetric(benchmarkId, params);
            add(metrics, ComparisonFamily.JMH, benchmarkId, params, primary, unit,
                "mean".equals(primary) ? number(row, "mean") : number(row, "p50"), concurrency,
                env.deploymentMode(), env.stateBackend(), "reference-jmh");
        }
        return new NormalizedBenchmarkSet(
            ComparisonFamily.JMH,
            "reference-baseline",
            "1",
            path,
            text(root, "commit"),
            false,
            text(root.path("jmhConfiguration"), "profile"),
            env,
            metrics);
    }

    static NormalizedBenchmarkSet loadRawJmhCandidate(Path path) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(path));
        Path manifest = siblingManifest(path);
        JsonNode manifestRoot = manifest != null ? JSON.readTree(Files.readString(manifest)) : null;
        EnvironmentFingerprint env = manifestRoot == null ? new EnvironmentFingerprint(null, null, null, null, null, null, null, null, null, null)
            : new EnvironmentFingerprint(
                text(manifestRoot.path("environment"), "os"),
                text(manifestRoot.path("environment"), "architecture"),
                text(manifestRoot.path("environment"), "javaVersion"),
                integer(manifestRoot.path("environment"), "processors"),
                longValue(manifestRoot.path("environment"), "maxHeapBytes"),
                text(manifestRoot, "featureSchemaVersion"),
                text(manifestRoot, "deploymentMode"),
                text(manifestRoot, "stateBackend"),
                text(manifestRoot, "suiteName"),
                null);
        List<NormalizedMetric> metrics = new ArrayList<>();
        if (!root.isArray()) {
            throw new IllegalArgumentException("Expected JMH JSON array: " + path);
        }
        for (JsonNode row : root) {
            Map<String, String> params = params(row.path("params"));
            String benchmarkId = text(row, "benchmark");
            String unit = text(row.path("primaryMetric"), "scoreUnit");
            Integer concurrency = integer(row, "threads");
            String primary = jmhPrimaryMetric(benchmarkId, params);
            JsonNode percentiles = row.path("primaryMetric").path("scorePercentiles");
            add(metrics, ComparisonFamily.JMH, benchmarkId, params, primary, unit,
                "mean".equals(primary) ? number(row.path("primaryMetric"), "score") : field(percentiles, "50.0"),
                concurrency, env.deploymentMode(), env.stateBackend(), "raw-jmh");
        }
        String profile = manifestRoot == null ? null : profileFromArgs(text(manifestRoot.path("run"), "jmhArgs"));
        String commit = manifestRoot == null ? null : text(manifestRoot, "commit");
        return new NormalizedBenchmarkSet(ComparisonFamily.JMH, "raw-jmh", "1", path, commit, null, profile, env, metrics);
    }

    static NormalizedBenchmarkSet loadDeployment(Path path) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(path));
        List<NormalizedMetric> metrics = new ArrayList<>();
        EnvironmentFingerprint env = new EnvironmentFingerprint(
            firstResultText(root, "os"),
            firstResultText(root, "architecture"),
            firstResultText(root, "javaVersion"),
            null,
            null,
            firstResultText(root, "featureSchemaVersion"),
            firstResultText(root, "deploymentMode"),
            firstResultText(root, "stateBackend"),
            root.path("suiteName").asText(null),
            null);
        for (JsonNode row : root.path("results")) {
            String benchmarkId = text(row, "scenario");
            Map<String, String> params = Map.of("scorer", text(row, "scorer"));
            Integer concurrency = integer(row, "concurrency");
            add(metrics, ComparisonFamily.DEPLOYMENT, benchmarkId, params, "successLatency.p50", text(row.path("successLatency"), "unit"),
                number(row.path("successLatency"), "p50"), concurrency, text(row, "deploymentMode"),
                text(row, "stateBackend"), "deployment-harness");
            add(metrics, ComparisonFamily.DEPLOYMENT, benchmarkId, params, "throughputRequestsPerSecond", "req/s",
                number(row, "throughputRequestsPerSecond"), concurrency, text(row, "deploymentMode"),
                text(row, "stateBackend"), "deployment-harness");
        }
        return new NormalizedBenchmarkSet(
            ComparisonFamily.DEPLOYMENT,
            text(root, "resultSchemaVersion"),
            text(root, "harnessVersion"),
            path,
            firstResultText(root, "gitCommit"),
            null,
            text(root.path("summary"), "mode"),
            env,
            metrics);
    }

    static NormalizedBenchmarkSet loadResources(Path path) throws IOException {
        JsonNode root = JSON.readTree(Files.readString(path));
        List<NormalizedMetric> metrics = new ArrayList<>();
        EnvironmentFingerprint env = new EnvironmentFingerprint(
            firstResultText(root, "os"),
            firstResultText(root, "architecture"),
            firstResultText(root, "javaVersion"),
            firstResultInt(root, "logicalProcessors"),
            firstResultLong(root, "heapMaxBytes"),
            null,
            firstResultText(root, "deploymentMode"),
            firstResultText(root, "stateBackend"),
            null,
            null);
        for (JsonNode row : root.path("results")) {
            String benchmarkId = text(row, "scenario");
            Map<String, String> params = Map.of("modelState", text(row, "modelState"));
            Integer concurrency = integer(row, "concurrency");
            add(metrics, ComparisonFamily.RESOURCES, benchmarkId, params, "processCpuCoresEquivalent", "cores",
                number(row, "processCpuCoresEquivalent"), concurrency, text(row, "deploymentMode"),
                text(row, "stateBackend"), "resource-sampler");
            add(metrics, ComparisonFamily.RESOURCES, benchmarkId, params, "heapPeakMeasuredBytes", "bytes",
                number(row, "heapPeakMeasuredBytes"), concurrency, text(row, "deploymentMode"),
                text(row, "stateBackend"), "resource-sampler");
            Double redisCpu = number(row, "redisContainerCpuPercent");
            if (redisCpu != null) {
                add(metrics, ComparisonFamily.RESOURCES, benchmarkId, params, "redisContainerCpuPercent", "percent",
                    redisCpu, concurrency, text(row, "deploymentMode"),
                    text(row, "stateBackend"), "resource-sampler");
            }
        }
        return new NormalizedBenchmarkSet(
            ComparisonFamily.RESOURCES,
            text(root, "schemaVersion"),
            text(root, "harnessVersion"),
            path,
            firstResultText(root, "gitCommit"),
            firstResultBoolean(root, "dirtyTree"),
            text(root.path("summary"), "mode"),
            env,
            metrics);
    }

    static NormalizedBenchmarkSet load(ComparisonFamily family, Path path, boolean baseline) throws IOException {
        return switch (family) {
            case JMH -> baseline ? loadReferenceBaseline(path) : loadRawJmhCandidate(path);
            case DEPLOYMENT -> loadDeployment(path);
            case RESOURCES -> loadResources(path);
        };
    }

    static String now() {
        return Instant.now().toString();
    }

    private static void add(List<NormalizedMetric> metrics, ComparisonFamily family, String benchmarkId, Map<String, String> params,
                            String metric, String unit, Double value, Integer concurrency, String deploymentMode,
                            String stateBackend, String measurementMethod) {
        metrics.add(new NormalizedMetric(
            family, benchmarkId, params, metric, unit, value, concurrency, deploymentMode, stateBackend, measurementMethod, false));
    }

    private static Map<String, String> params(JsonNode node) {
        Map<String, String> params = new TreeMap<>();
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                params.put(field.getKey(), field.getValue().asText(""));
            }
        }
        return Map.copyOf(params);
    }

    private static String jmhPrimaryMetric(String benchmarkId, Map<String, String> params) {
        if (benchmarkId.contains("Throughput") || "isolationForestFallback".equals(params.get("scorerKind"))) {
            return "mean";
        }
        return "p50";
    }

    private static String profileFromArgs(String jmhArgs) {
        if (jmhArgs == null) {
            return null;
        }
        return jmhArgs.contains("-f 2") && jmhArgs.contains("-wi 5") && jmhArgs.contains("-i 5") ? "reference" : "ad-hoc";
    }

    private static Path siblingManifest(Path path) {
        String name = path.getFileName().toString();
        if (!name.startsWith("jmh-")) {
            return null;
        }
        Path manifest = path.resolveSibling("manifest-" + name.substring(4));
        return Files.exists(manifest) ? manifest : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asInt();
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asLong();
    }

    private static Double number(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asDouble();
    }

    private static Double field(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child == null || child.isNull() ? null : child.asDouble();
    }

    private static String firstResultText(JsonNode root, String field) {
        JsonNode results = root.path("results");
        return results.isArray() && !results.isEmpty() ? text(results.get(0), field) : null;
    }

    private static Integer firstResultInt(JsonNode root, String field) {
        JsonNode results = root.path("results");
        return results.isArray() && !results.isEmpty() ? integer(results.get(0), field) : null;
    }

    private static Long firstResultLong(JsonNode root, String field) {
        JsonNode results = root.path("results");
        return results.isArray() && !results.isEmpty() ? longValue(results.get(0), field) : null;
    }

    private static Boolean firstResultBoolean(JsonNode root, String field) {
        JsonNode results = root.path("results");
        if (results.isArray() && !results.isEmpty()) {
            JsonNode child = results.get(0).get(field);
            return child == null || child.isNull() ? null : child.asBoolean();
        }
        return null;
    }
}
