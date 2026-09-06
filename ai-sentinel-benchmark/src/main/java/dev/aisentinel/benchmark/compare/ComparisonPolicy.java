package dev.aisentinel.benchmark.compare;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class ComparisonPolicy {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String SCHEMA_VERSION = "1";

    private final String schemaVersion;
    private final List<MetricPolicy> policies;
    private final String sha256;

    private ComparisonPolicy(String schemaVersion, List<MetricPolicy> policies, String sha256) {
        this.schemaVersion = schemaVersion;
        this.policies = List.copyOf(policies);
        this.sha256 = sha256;
    }

    static ComparisonPolicy loadDefault() throws IOException {
        try (InputStream in = ComparisonPolicy.class.getResourceAsStream("/benchmark-comparison-policy.json")) {
            if (in == null) {
                throw new IOException("Missing default benchmark comparison policy");
            }
            byte[] bytes = in.readAllBytes();
            return parse(bytes);
        }
    }

    static ComparisonPolicy load(Path path) throws IOException {
        return parse(Files.readAllBytes(path));
    }

    String schemaVersion() {
        return schemaVersion;
    }

    String sha256() {
        return sha256;
    }

    MetricPolicy findPolicy(NormalizedMetric metric) {
        for (MetricPolicy policy : policies) {
            if (policy.matches(metric)) {
                return policy;
            }
        }
        return null;
    }

    private static ComparisonPolicy parse(byte[] bytes) throws IOException {
        JsonNode root = JSON.readTree(bytes);
        String schemaVersion = root.path("policySchemaVersion").asText(null);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported policy schema version: " + schemaVersion);
        }
        List<MetricPolicy> policies = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : root.path("policies")) {
            MetricPolicy policy = MetricPolicy.from(node);
            String key = policy.uniquenessKey();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Duplicate metric policy: " + key);
            }
            policies.add(policy);
        }
        return new ComparisonPolicy(schemaVersion, policies, sha256(bytes));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash policy", e);
        }
    }

    record MetricPolicy(
        ComparisonFamily family,
        String benchmarkId,
        Map<String, String> params,
        String metric,
        MetricDirection direction,
        Double warnThresholdPercent,
        Double regressionThresholdPercent,
        boolean gateEligible
    ) {
        static MetricPolicy from(JsonNode node) {
            ComparisonFamily family = ComparisonFamily.valueOf(node.path("family").asText(""));
            String benchmarkId = required(node, "benchmarkId");
            String metric = required(node, "metric");
            MetricDirection direction = MetricDirection.valueOf(required(node, "direction"));
            Double warn = nullableDouble(node.get("warnThresholdPercent"));
            Double regression = nullableDouble(node.get("regressionThresholdPercent"));
            boolean gateEligible = node.path("gateEligible").asBoolean(false);
            if (warn != null && warn < 0.0) {
                throw new IllegalArgumentException("warnThresholdPercent must be >= 0 for " + benchmarkId);
            }
            if (regression != null && regression < 0.0) {
                throw new IllegalArgumentException("regressionThresholdPercent must be >= 0 for " + benchmarkId);
            }
            if (warn != null && regression != null && warn > regression) {
                throw new IllegalArgumentException("warnThresholdPercent must be <= regressionThresholdPercent for " + benchmarkId);
            }
            Map<String, String> params = new TreeMap<>();
            JsonNode paramsNode = node.get("params");
            if (paramsNode != null && paramsNode.isObject()) {
                paramsNode.fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText("")));
            }
            return new MetricPolicy(family, benchmarkId, Map.copyOf(params), metric, direction, warn, regression, gateEligible);
        }

        boolean matches(NormalizedMetric metric) {
            return family == metric.family()
                && benchmarkId.equals(metric.benchmarkId())
                && this.metric.equals(metric.metric())
                && params.equals(metric.params());
        }

        String uniquenessKey() {
            return family + "|" + benchmarkId + "|" + params + "|" + metric;
        }

        private static String required(JsonNode node, String field) {
            String value = node.path(field).asText(null);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing policy field: " + field);
            }
            return value;
        }

        private static Double nullableDouble(JsonNode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            double value = node.asDouble();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Non-finite threshold");
            }
            return value;
        }
    }
}
