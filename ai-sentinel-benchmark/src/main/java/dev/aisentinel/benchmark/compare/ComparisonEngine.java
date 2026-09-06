package dev.aisentinel.benchmark.compare;

import dev.aisentinel.benchmark.compare.ComparisonPolicy.MetricPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ComparisonEngine {

    static final String COMPARISON_SCHEMA_VERSION = "1";
    static final String COMPARISON_TOOL_VERSION = "1";

    private ComparisonEngine() {
    }

    static ComparisonReport compare(NormalizedBenchmarkSet baseline,
                                    NormalizedBenchmarkSet candidate,
                                    ComparisonPolicy policy) {
        Map<String, NormalizedMetric> baselineByKey = index(baseline.metrics());
        Map<String, NormalizedMetric> candidateByKey = index(candidate.metrics());
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(baselineByKey.keySet());
        keys.addAll(candidateByKey.keySet());

        List<String> sortedKeys = new ArrayList<>(keys);
        sortedKeys.sort(Comparator.naturalOrder());

        List<MetricComparison> comparisons = new ArrayList<>();
        int pass = 0;
        int warn = 0;
        int regression = 0;
        int improvement = 0;
        int notComparable = 0;
        int gateRegression = 0;

        for (String key : sortedKeys) {
            NormalizedMetric baseMetric = baselineByKey.get(key);
            NormalizedMetric candidateMetric = candidateByKey.get(key);
            MetricComparison comparison = compareMetric(baseMetric, candidateMetric, baseline, candidate, policy);
            comparisons.add(comparison);
            switch (comparison.classification()) {
                case PASS -> pass++;
                case WARN -> warn++;
                case REGRESSION -> {
                    regression++;
                    if (comparison.gateEligible() && comparison.comparabilityStatus() == ComparabilityStatus.STRICT_COMPARABLE) {
                        gateRegression++;
                    }
                }
                case IMPROVEMENT -> improvement++;
                case NOT_COMPARABLE -> notComparable++;
            }
        }
        return new ComparisonReport(
            COMPARISON_SCHEMA_VERSION,
            COMPARISON_TOOL_VERSION,
            ComparisonAdapters.now(),
            baseline.family(),
            baseline.source(),
            candidate.source(),
            policy.schemaVersion(),
            policy.sha256(),
            baseline,
            candidate,
            new Summary(pass, warn, regression, improvement, notComparable, gateRegression),
            comparisons);
    }

    static int exitCode(ComparisonReport report) {
        return report.summary().gateRegressionCount() > 0 ? 1 : 0;
    }

    private static MetricComparison compareMetric(NormalizedMetric baselineMetric,
                                                  NormalizedMetric candidateMetric,
                                                  NormalizedBenchmarkSet baseline,
                                                  NormalizedBenchmarkSet candidate,
                                                  ComparisonPolicy policy) {
        if (baselineMetric == null) {
            return missing(candidateMetric, ComparisonReason.MISSING_BASELINE);
        }
        if (candidateMetric == null) {
            return missing(baselineMetric, ComparisonReason.MISSING_CANDIDATE);
        }
        MetricPolicy metricPolicy = policy.findPolicy(baselineMetric);
        if (metricPolicy == null) {
            return notComparable(baselineMetric, candidateMetric, ComparisonReason.POLICY_MISSING, false, null, null);
        }
        Double candidateValue = UnitConverter.convert(candidateMetric.value(), candidateMetric.unit(), baselineMetric.unit());
        if (candidateValue == null && candidateMetric.value() != null && baselineMetric.value() != null) {
            return notComparable(baselineMetric, candidateMetric, ComparisonReason.UNIT_MISMATCH, metricPolicy.gateEligible(),
                metricPolicy.warnThresholdPercent(), metricPolicy.regressionThresholdPercent());
        }
        List<ComparisonReason> reasons = new ArrayList<>();
        ComparabilityStatus status = comparability(baseline, candidate, baselineMetric, candidateMetric, reasons);
        if (baselineMetric.value() == null || candidateValue == null) {
            reasons.add(ComparisonReason.VALUE_MISSING);
            status = ComparabilityStatus.NOT_COMPARABLE;
        }
        if (status == ComparabilityStatus.NOT_COMPARABLE) {
            return new MetricComparison(
                baselineMetric.benchmarkId(), baselineMetric.params(), baselineMetric.metric(), baselineMetric.unit(),
                baselineMetric.concurrency(), metricPolicy.direction(), false, status, List.copyOf(reasons),
                baselineMetric.value(), candidateValue, null, null, ComparisonClassification.NOT_COMPARABLE,
                metricPolicy.warnThresholdPercent(), metricPolicy.regressionThresholdPercent());
        }
        Double baselineValue = baselineMetric.value();
        Double absoluteDelta = candidateValue - baselineValue;
        Double percentageDelta = baselineValue == 0.0 ? null : (absoluteDelta / baselineValue) * 100.0;
        if (baselineValue == 0.0 && candidateValue != 0.0) {
            reasons.add(ComparisonReason.ZERO_BASELINE);
            return new MetricComparison(
                baselineMetric.benchmarkId(), baselineMetric.params(), baselineMetric.metric(), baselineMetric.unit(),
                baselineMetric.concurrency(), metricPolicy.direction(), false, ComparabilityStatus.NOT_COMPARABLE, List.copyOf(reasons),
                baselineValue, candidateValue, absoluteDelta, null, ComparisonClassification.NOT_COMPARABLE,
                metricPolicy.warnThresholdPercent(), metricPolicy.regressionThresholdPercent());
        }
        ComparisonClassification classification = classify(metricPolicy, percentageDelta);
        boolean gateEligible = metricPolicy.gateEligible() && status == ComparabilityStatus.STRICT_COMPARABLE;
        return new MetricComparison(
            baselineMetric.benchmarkId(), baselineMetric.params(), baselineMetric.metric(), baselineMetric.unit(),
            baselineMetric.concurrency(), metricPolicy.direction(), gateEligible, status, List.copyOf(reasons),
            baselineValue, candidateValue, absoluteDelta, percentageDelta, classification,
            metricPolicy.warnThresholdPercent(), metricPolicy.regressionThresholdPercent());
    }

    private static ComparisonClassification classify(MetricPolicy policy, Double percentageDelta) {
        if (percentageDelta == null) {
            return ComparisonClassification.PASS;
        }
        double worse = policy.direction() == MetricDirection.LOWER_IS_BETTER ? percentageDelta : -percentageDelta;
        double better = -worse;
        if (policy.regressionThresholdPercent() != null && worse >= policy.regressionThresholdPercent()) {
            return ComparisonClassification.REGRESSION;
        }
        if (policy.warnThresholdPercent() != null && worse >= policy.warnThresholdPercent()) {
            return ComparisonClassification.WARN;
        }
        if (policy.warnThresholdPercent() != null && better >= policy.warnThresholdPercent()) {
            return ComparisonClassification.IMPROVEMENT;
        }
        return ComparisonClassification.PASS;
    }

    private static ComparabilityStatus comparability(NormalizedBenchmarkSet baseline,
                                                     NormalizedBenchmarkSet candidate,
                                                     NormalizedMetric baselineMetric,
                                                     NormalizedMetric candidateMetric,
                                                     List<ComparisonReason> reasons) {
        if (baseline.profile() != null && candidate.profile() != null && !baseline.profile().equals(candidate.profile())) {
            reasons.add(ComparisonReason.PROFILE_MISMATCH);
            return ComparabilityStatus.NOT_COMPARABLE;
        }
        if (baseline.family() != candidate.family()) {
            reasons.add(ComparisonReason.MEASUREMENT_METHOD_MISMATCH);
            return ComparabilityStatus.NOT_COMPARABLE;
        }
        boolean envMismatch = !equalsNullSafe(baseline.environment().os(), candidate.environment().os())
            || !equalsNullSafe(baseline.environment().architecture(), candidate.environment().architecture())
            || !sameJavaMajor(baseline.environment().javaVersion(), candidate.environment().javaVersion())
            || !equalsNullSafe(baseline.environment().logicalCpus(), candidate.environment().logicalCpus())
            || !equalsNullSafe(baseline.environment().maxHeapBytes(), candidate.environment().maxHeapBytes());
        if (candidate.dirtyTree() != null && candidate.dirtyTree()) {
            reasons.add(ComparisonReason.DIRTY_CANDIDATE);
            return ComparabilityStatus.INFORMATIONAL_ONLY;
        }
        if (envMismatch) {
            reasons.add(ComparisonReason.ENVIRONMENT_MISMATCH);
            return ComparabilityStatus.INFORMATIONAL_ONLY;
        }
        return ComparabilityStatus.STRICT_COMPARABLE;
    }

    private static boolean sameJavaMajor(String a, String b) {
        if (a == null || b == null) {
            return true;
        }
        return a.split("\\.")[0].equals(b.split("\\.")[0]);
    }

    private static boolean equalsNullSafe(Object left, Object right) {
        return left == null || right == null || left.equals(right);
    }

    private static MetricComparison missing(NormalizedMetric metric, ComparisonReason reason) {
        return new MetricComparison(
            metric.benchmarkId(), metric.params(), metric.metric(), metric.unit(), metric.concurrency(), MetricDirection.LOWER_IS_BETTER,
            false, ComparabilityStatus.NOT_COMPARABLE, List.of(reason), null, null, null, null,
            ComparisonClassification.NOT_COMPARABLE, null, null);
    }

    private static MetricComparison notComparable(NormalizedMetric baselineMetric,
                                                  NormalizedMetric candidateMetric,
                                                  ComparisonReason reason,
                                                  boolean gateEligible,
                                                  Double warnThreshold,
                                                  Double regressionThreshold) {
        return new MetricComparison(
            baselineMetric.benchmarkId(), baselineMetric.params(), baselineMetric.metric(), baselineMetric.unit(),
            baselineMetric.concurrency(), MetricDirection.LOWER_IS_BETTER, gateEligible,
            ComparabilityStatus.NOT_COMPARABLE, List.of(reason),
            baselineMetric.value(), candidateMetric.value(), null, null,
            ComparisonClassification.NOT_COMPARABLE, warnThreshold, regressionThreshold);
    }

    private static Map<String, NormalizedMetric> index(List<NormalizedMetric> metrics) {
        Map<String, NormalizedMetric> map = new LinkedHashMap<>();
        for (NormalizedMetric metric : metrics) {
            if (map.put(metric.key(), metric) != null) {
                throw new IllegalArgumentException("Duplicate benchmark identity: " + metric.key());
            }
        }
        return map;
    }
}
