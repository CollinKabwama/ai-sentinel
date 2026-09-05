package dev.aisentinel.benchmark;

import org.openjdk.jmh.Main;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Entry point for opt-in JMH runs.
 * Writes an AI-Sentinel environment manifest beside JMH JSON output, then delegates to JMH.
 *
 * <p>Default smoke args are used when no JMH arguments are supplied.
 */
public final class BenchmarkLauncher {

    private BenchmarkLauncher() {
    }

    public static void main(String[] args) throws Exception {
        Path resultsDir = Path.of(System.getProperty(
            "aisentinel.benchmark.resultsDir",
            "ai-sentinel-benchmark/results"));
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
            .format(LocalDateTime.now());
        Path manifestPath = resultsDir.resolve("manifest-" + stamp + ".json");
        Path jmhJsonPath = resultsDir.resolve("jmh-" + stamp + ".json");

        List<String> jmhArgs = new ArrayList<>();
        if (args.length == 0) {
            // Quick smoke: short warmup/measurement, single fork, subset of methods.
            jmhArgs.add(".*ScorerLatencyBenchmark.score");
            jmhArgs.add(".*DecisionEngineBenchmark.evaluate");
            jmhArgs.add(".*FeatureExtractionBenchmark.extract");
            jmhArgs.add("-p");
            jmhArgs.add("scorerKind=statistical");
            jmhArgs.add("-p");
            jmhArgs.add("workload=establishedBaseline");
            jmhArgs.add("-p");
            jmhArgs.add("requestShape=typical");
            jmhArgs.add("-f");
            jmhArgs.add("1");
            jmhArgs.add("-wi");
            jmhArgs.add("1");
            jmhArgs.add("-i");
            jmhArgs.add("2");
            jmhArgs.add("-r");
            jmhArgs.add("250ms");
            jmhArgs.add("-w");
            jmhArgs.add("250ms");
        } else {
            for (String arg : args) {
                jmhArgs.add(arg);
            }
        }

        boolean hasResultFormat = jmhArgs.stream().anyMatch("-rf"::equals);
        boolean hasResultFile = jmhArgs.stream().anyMatch("-rff"::equals);
        if (!hasResultFormat) {
            jmhArgs.add("-rf");
            jmhArgs.add("json");
        }
        if (!hasResultFile) {
            jmhArgs.add("-rff");
            jmhArgs.add(jmhJsonPath.toString());
        }

        EnvironmentMetadata metadata = EnvironmentMetadataCollector.collect();
        Map<String, String> extras = new LinkedHashMap<>();
        extras.put("jmhArgs", String.join(" ", jmhArgs));
        extras.put("jmhResultFile", jmhJsonPath.toString());
        BenchmarkManifestWriter.write(manifestPath, metadata, extras);

        System.out.println("Wrote benchmark manifest: " + manifestPath.toAbsolutePath());
        System.out.println("JMH JSON results: " + jmhJsonPath.toAbsolutePath());
        System.out.println(
            "NOTE: Results are synthetic host-local measurements — not production SLAs or detection claims.");

        Main.main(jmhArgs.toArray(String[]::new));
    }
}
