package dev.aisentinel.benchmark.compare;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class ComparisonReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ComparisonReportWriter() {
    }

    static void writeJson(Path output, ComparisonReport report) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        JSON.writeValue(output.toFile(), report);
    }

    static void writeHumanSummary(PrintStream out, ComparisonReport report) {
        out.println("Benchmark comparison");
        out.println("--------------------");
        out.printf("Comparable strict: %d%n", report.comparisons().stream()
            .filter(c -> c.comparabilityStatus() == ComparabilityStatus.STRICT_COMPARABLE).count());
        out.printf("Informational only: %d%n", report.comparisons().stream()
            .filter(c -> c.comparabilityStatus() == ComparabilityStatus.INFORMATIONAL_ONLY).count());
        out.printf("Pass: %d%n", report.summary().passCount());
        out.printf("Warnings: %d%n", report.summary().warnCount());
        out.printf("Regressions: %d%n", report.summary().regressionCount());
        out.printf("Improvements: %d%n", report.summary().improvementCount());
        out.printf("Not comparable: %d%n", report.summary().notComparableCount());
        out.printf("Gate regressions: %d%n", report.summary().gateRegressionCount());
        for (MetricComparison comparison : report.comparisons()) {
            if (comparison.classification() == ComparisonClassification.PASS) {
                continue;
            }
            out.printf("%n%s %s %s", comparison.classification(), comparison.benchmarkId(), comparison.metric());
            if (!comparison.params().isEmpty()) {
                out.printf(" %s", comparison.params());
            }
            out.println();
            out.printf("baseline: %s %s%n", display(comparison.baselineValue()), comparison.unit());
            out.printf("candidate: %s %s%n", display(comparison.candidateValue()), comparison.unit());
            out.printf("delta: %s", display(comparison.absoluteDelta()));
            if (comparison.percentageDelta() != null) {
                out.printf(" (%+.2f%%)%n", comparison.percentageDelta());
            } else {
                out.println(" (percentage unavailable)");
            }
            if (!comparison.reasons().isEmpty()) {
                out.printf("reasons: %s%n", comparison.reasons());
            }
            if (comparison.regressionThresholdPercent() != null) {
                out.printf("thresholds: warn %s%%, regression %s%%%n",
                    display(comparison.warnThresholdPercent()), display(comparison.regressionThresholdPercent()));
            }
        }
    }

    private static String display(Double value) {
        return value == null ? "n/a" : String.format("%.3f", value);
    }
}
