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
    void zeroBaselineAndZeroCandidateDoesNotInventPercentageDelta() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.RESOURCES, false, "full",
            metric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS",
                Map.of("modelState", "statistical"), "processCpuCoresEquivalent", "cores", 0.0, 1));
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.RESOURCES, false, "full",
            metric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS",
                Map.of("modelState", "statistical"), "processCpuCoresEquivalent", "cores", 0.0, 1));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.PASS);
        assertThat(report.comparisons().get(0).percentageDelta()).isNull();
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

    @Test
    void convertibleUnitDifferenceStillComparesSameMetricIdentity() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ns/op", 1_000_000.0, 1));
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ms/op", 1.1, 1));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons()).hasSize(1);
        assertThat(report.comparisons().get(0).candidateValue()).isEqualTo(1_100_000.0);
        assertThat(report.comparisons().get(0).percentageDelta()).isEqualTo(10.0);
        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.WARN);
    }

    @Test
    void incompatibleUnitDifferenceIsNotComparable() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ns/op", 100.0, 1));
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ops/s", 100.0, 1));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons()).hasSize(1);
        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.NOT_COMPARABLE);
        assertThat(report.comparisons().get(0).reasons()).containsExactly(ComparisonReason.UNIT_MISMATCH);
    }

    @Test
    void invalidNumericValuesAreNotComparable() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        for (double invalidValue : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            NormalizedBenchmarkSet baseline = set(ComparisonFamily.JMH, false, "reference",
                metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                    Map.of("scorerKind", "statistical"), "p50", "ns/op", 100.0, 1));
            NormalizedBenchmarkSet candidate = set(ComparisonFamily.JMH, false, "reference",
                metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                    Map.of("scorerKind", "statistical"), "p50", "ns/op", invalidValue, 1));

            ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

            assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.NOT_COMPARABLE);
            assertThat(report.comparisons().get(0).reasons()).contains(ComparisonReason.INVALID_VALUE);
        }
    }

    @Test
    void missingEnvironmentMetadataIsInformationalOnly() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ns/op", 100.0, 1));
        NormalizedBenchmarkSet candidate = new NormalizedBenchmarkSet(
            ComparisonFamily.JMH, "raw-jmh", "1", Path.of("candidate.json"), "candidate", false, "reference",
            new EnvironmentFingerprint(null, null, null, null, null, "1", "in-process", "local-memory", "suite", "env"),
            List.of(metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ns/op", 130.0, 1)));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).comparabilityStatus()).isEqualTo(ComparabilityStatus.INFORMATIONAL_ONLY);
        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.REGRESSION);
        assertThat(report.comparisons().get(0).gateEligible()).isFalse();
        assertThat(ComparisonEngine.exitCode(report)).isZero();
    }

    @Test
    void dirtyCandidateRegressionIsInformationalOnly() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ns/op", 100.0, 1));
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.JMH, true, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ns/op", 150.0, 1));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).comparabilityStatus()).isEqualTo(ComparabilityStatus.INFORMATIONAL_ONLY);
        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.REGRESSION);
        assertThat(report.comparisons().get(0).gateEligible()).isFalse();
        assertThat(ComparisonEngine.exitCode(report)).isZero();
    }

    @Test
    void profileMismatchIsNotComparable() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.JMH, false, "reference",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ns/op", 100.0, 1));
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.JMH, false, "ad-hoc",
            metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
                Map.of("scorerKind", "statistical"), "p50", "ns/op", 150.0, 1));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.NOT_COMPARABLE);
        assertThat(report.comparisons().get(0).reasons()).containsExactly(ComparisonReason.PROFILE_MISMATCH);
    }

    @Test
    void schemaMismatchIsNotComparable() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.RESOURCES, false, "full",
            metric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS",
                Map.of("modelState", "statistical"), "processCpuCoresEquivalent", "cores", 1.0, 1));
        NormalizedBenchmarkSet candidate = new NormalizedBenchmarkSet(
            ComparisonFamily.RESOURCES, "999", "1", Path.of("candidate.json"), "candidate", false, "full",
            baseline.environment(),
            List.of(metric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS",
                Map.of("modelState", "statistical"), "processCpuCoresEquivalent", "cores", 1.5, 1)));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.NOT_COMPARABLE);
        assertThat(report.comparisons().get(0).reasons()).containsExactly(ComparisonReason.SCHEMA_VERSION_MISMATCH);
    }

    @Test
    void measurementMethodMismatchIsNotComparable() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.RESOURCES, false, "full",
            metric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS",
                Map.of("modelState", "statistical"), "processCpuCoresEquivalent", "cores", 1.0, 1));
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.RESOURCES, false, "full",
            new NormalizedMetric(ComparisonFamily.RESOURCES, "RESOURCE_IN_PROCESS", Map.of("modelState", "statistical"),
                "processCpuCoresEquivalent", "cores", 1.5, 1, "in-process", "local-memory", "other-method", false));

        ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);

        assertThat(report.comparisons().get(0).classification()).isEqualTo(ComparisonClassification.NOT_COMPARABLE);
        assertThat(report.comparisons().get(0).reasons()).containsExactly(ComparisonReason.MEASUREMENT_METHOD_MISMATCH);
    }

    @Test
    void duplicateMetricIdentityIsRejected() {
        NormalizedMetric first = metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
            Map.of("scorerKind", "statistical"), "p50", "ns/op", 100.0, 1);
        NormalizedMetric second = metric(ComparisonFamily.JMH, "dev.aisentinel.benchmark.jmh.ScorerLatencyBenchmark.score",
            Map.of("scorerKind", "statistical"), "p50", "ms/op", 0.1, 1);
        NormalizedBenchmarkSet baseline = set(ComparisonFamily.JMH, false, "reference", first, second);
        NormalizedBenchmarkSet candidate = set(ComparisonFamily.JMH, false, "reference");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ComparisonEngine.compare(baseline, candidate, ComparisonPolicy.loadDefault()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate benchmark identity");
    }

    private NormalizedBenchmarkSet set(ComparisonFamily family, boolean dirty, String profile, NormalizedMetric... metrics) {
        return new NormalizedBenchmarkSet(
            family, family == ComparisonFamily.JMH ? "reference-baseline" : "1", "1", Path.of("fixture.json"), "commit", dirty, profile,
            new EnvironmentFingerprint("Mac OS X", "aarch64", "21.0.10", 10, 6442450944L, "1", "in-process", "local-memory", "suite", "env"),
            List.of(metrics));
    }

    private NormalizedMetric metric(ComparisonFamily family, String benchmarkId, Map<String, String> params,
                                    String metric, String unit, Double value, Integer concurrency) {
        String method = family == ComparisonFamily.JMH ? "reference-jmh" : family.name().toLowerCase();
        return new NormalizedMetric(family, benchmarkId, params, metric, unit, value, concurrency, "in-process", "local-memory",
            method, false);
    }
}
