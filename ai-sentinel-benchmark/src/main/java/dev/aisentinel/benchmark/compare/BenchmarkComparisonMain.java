package dev.aisentinel.benchmark.compare;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class BenchmarkComparisonMain {

    private BenchmarkComparisonMain() {
    }

    public static void main(String[] args) throws Exception {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) throws Exception {
        try {
            Map<String, String> options = parse(args);
            ComparisonFamily family = ComparisonFamily.valueOf(required(options, "family").toUpperCase());
            Path baselinePath = Path.of(required(options, "baseline"));
            Path candidatePath = Path.of(required(options, "candidate"));
            Path outputPath = Path.of(options.getOrDefault("output", defaultOutput(family)));
            ComparisonPolicy policy = options.containsKey("policy")
                ? ComparisonPolicy.load(Path.of(options.get("policy")))
                : ComparisonPolicy.loadDefault();
            NormalizedBenchmarkSet baseline = ComparisonAdapters.load(family, baselinePath, true);
            NormalizedBenchmarkSet candidate = ComparisonAdapters.load(family, candidatePath, false);
            ComparisonReport report = ComparisonEngine.compare(baseline, candidate, policy);
            ComparisonReportWriter.writeJson(outputPath, report);
            ComparisonReportWriter.writeHumanSummary(out, report);
            out.println();
            out.println("Comparison report: " + outputPath.toAbsolutePath());
            return ComparisonEngine.exitCode(report);
        } catch (Exception e) {
            err.println(e.getMessage());
            return 2;
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Usage: --family <jmh|deployment|resources> --baseline <path> --candidate <path> [--policy <path>] [--output <path>]");
            }
            options.put(arg.substring(2), args[++i]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required argument --" + key);
        }
        return value;
    }

    private static String defaultOutput(ComparisonFamily family) {
        return "ai-sentinel-benchmark/results/comparisons/" + family.name().toLowerCase() + "-comparison.json";
    }
}
