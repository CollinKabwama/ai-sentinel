package dev.aisentinel.benchmark.compare;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonEngineTest {

    @Test
    void lowerIsBetterWarnsAtInclusiveThreshold() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ns/op", 100.0, 1));
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ns/op", 110.0, 1));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.WARN);
    }

    @Test
    void higherIsBetterTreatsLowerThroughputAsRegression() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.PipelineBenchmark.processThroughputOneThread",
                Map.of(), "mean", "ops/s", 1000.0, 1));
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.PipelineBenchmark.processThroughputOneThread",
                Map.of(), "mean", "ops/s", 650.0, 1));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.REGRESSION);
        assertThat(report.summary().gateRegressionCount()).isEqualTo(1);
    }

    @Test
    void environmentMismatchBecomesInformationalOnly() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.RESOURCES, false, "full",
            metric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS",
                Map.of("modelState", "statistical"), "processCpuCoresEquivalent", "cores", 1.0, 1));
        NormalizedBenchmarkSet candidate = new NormalizedBenchmarkSet(
            ComparisonFamily.RESOURCES, "1", "1", Path.of("candidate.json"), "candidate", false, "full",
            new EnvironmentFingerprint("Linux", "x86_64", "21.0.10", 8, 1024L, null, null, null, null, null),
            List.of(metric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS",
                Map.of("modelState", "statistical"), "processCpuCoresEquivalent", "cores", 1.6, 1)));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).comparabilityStatus()).isEqualTo(ComparabilityStatus.INFORMATIONAL_ONLY);
        assertThat(report.comparisons().get(0).gateEligible()).isFalse();
    }

    @Test
    void zeroBaselineIsNotComparable() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.RESOURCES, false, "full",
            metric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS",
                Map.of("modelState", "statistical"), "processCpuCoresEquivalent", "cores", 0.0, 1));
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.RESOURCES, false, "full",
            metric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS",
                Map.of("modelState", "statistical"), "processCpuCoresEquivalent", "cores", 1.0, 1));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.NOT_COMPARABLE);
    }

    @Test
    void missingMetricIsNotComparable() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.RESOURCES, false, "full",
            metric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS",
                Map.of("modelState", "statistical"), "processCpuCoresEquivalent", "cores", 1.0, 1));
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.RESOURCES, false, "full");

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.NOT_COMPARABLE);
    }

    private NormalizedBenchmarkSet set(ComparisonFamily family, boolean dirty, String profile, NormalizedMetric... metrics) {
        return new NormalizedBenchmarkSet(
            family, "1", "1", Path.of("fixture.json"), "commit", dirty, profile,
            new EnvironmentFingerprint("Mac OS X", "aarch64", "21.0.10", 10, 6442450944L, "1", "in-process", "local-memory", "suite", "env"),
            List.of(metrics));
    }

    private NormalizedMetric metric(ComparisonFamily family, String benchmarkId, Map<String, String> params,
                                    String metric, String unit, Double value, Integer concurrency) {
        return new NormalizedMetric(family, benchmarkId, params, metric, unit, value, concurrency, "in-process", "local-memory",
            family.name().toLowerCase(), false);
    }
}
