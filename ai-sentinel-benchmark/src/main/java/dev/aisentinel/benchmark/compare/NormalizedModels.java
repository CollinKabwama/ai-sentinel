package dev.aisentinel.benchmark.compare;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

record NormalizedBenchmarkSet(
    ComparisonFamily family,
    String schemaVersion,
    String toolVersion,
    Path source,
    String commit,
    Boolean dirtyTree,
    String profile,
    EnvironmentFingerprint environment,
    List<NormalizedMetric> metrics
) {
}

record EnvironmentFingerprint(
    String os,
    String architecture,
    String javaVersion,
    Integer logicalCpus,
    Long maxHeapBytes,
    String featureSchemaVersion,
    String deploymentMode,
    String stateBackend,
    String suiteName,
    String environmentId
) {
}

record NormalizedMetric(
    ComparisonFamily family,
    String benchmarkId,
    Map<String, String> params,
    String metric,
    String unit,
    Double value,
    Integer concurrency,
    String deploymentMode,
    String stateBackend,
    String measurementMethod,
    boolean gatePreferred
) {
    NormalizedMetric {
        params = params == null ? Map.of() : Map.copyOf(new TreeMap<>(params));
    }

    String key() {
        return family + "|" + benchmarkId + "|" + params + "|" + metric + "|" + unit + "|" + concurrency
            + "|" + deploymentMode + "|" + stateBackend;
    }
}

record MetricComparison(
    String benchmarkId,
    Map<String, String> params,
    String metric,
    String unit,
    Integer concurrency,
    MetricDirection direction,
    boolean gateEligible,
    ComparabilityStatus comparabilityStatus,
    List<ComparisonReason> reasons,
    Double baselineValue,
    Double candidateValue,
    Double absoluteDelta,
    Double percentageDelta,
    ComparisonClassification classification,
    Double warnThresholdPercent,
    Double regressionThresholdPercent
) {
}

record ComparisonReport(
    String comparisonSchemaVersion,
    String comparisonToolVersion,
    String timestampUtc,
    ComparisonFamily family,
    Path baselineSource,
    Path candidateSource,
    String policySchemaVersion,
    String policySha256,
    NormalizedBenchmarkSet baseline,
    NormalizedBenchmarkSet candidate,
    Summary summary,
    List<MetricComparison> comparisons
) {
}

record Summary(
    int passCount,
    int warnCount,
    int regressionCount,
    int improvementCount,
    int notComparableCount,
    int gateRegressionCount
) {
}
