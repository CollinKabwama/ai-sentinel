package dev.aisentinel.core.evaluation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

final class EvaluationComparisonCli {
    static final int EXIT_SUCCESS = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_USAGE = 2;

    private EvaluationComparisonCli() {
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        try {
            Arguments parsed = parse(args);
            if (parsed.help()) {
                printHelp(out);
                return EXIT_SUCCESS;
            }
            return compare(parsed, out);
        } catch (UsageException ex) {
            err.println(ex.getMessage());
            err.println();
            printHelp(err);
            return EXIT_USAGE;
        } catch (GeneratedCorpusEvaluationException ex) {
            err.println("ERROR [" + ex.failureKind() + "]: " + ex.getMessage());
            return EXIT_FAILURE;
        } catch (IOException ex) {
            err.println("ERROR [EVALUATION_FAILURE]: " + ex.getMessage());
            return EXIT_FAILURE;
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            err.println("ERROR [EVALUATION_FAILURE]: " + message);
            return EXIT_FAILURE;
        }
    }

    private static int compare(Arguments args, PrintStream out) throws IOException {
        Path output = args.output();
        boolean temporary = output == null;
        if (temporary) {
            output = Files.createTempDirectory("ai-sentinel-evaluation-comparison-");
            Files.delete(output);
        }
        try {
            EvaluationComparisonResult result = new EvaluationComparisonEngine().compare(
                args.baseline(), args.candidate(), output);
            out.println("Status: SUCCESS");
            out.println("Evaluation comparison completed.");
            out.println("  eventsCompared: " + result.eventsCompared());
            out.println("  eventsWithChanges: " + result.eventsWithChanges());
            out.println("  newFalsePositives: " + result.newFalsePositives());
            out.println("  newFalseNegatives: " + result.newFalseNegatives());
            out.println("  thresholdEqual: " + result.thresholdEqual());
            out.println("  comparisonJson: " + result.comparisonJson());
            out.println("  comparisonHtml: " + result.comparisonHtml());
            if (temporary) {
                out.println("  note: temporary artifacts are removed after this command");
            }
            return EXIT_SUCCESS;
        } finally {
            if (temporary) {
                deleteRecursively(output);
            }
        }
    }

    private static Arguments parse(String[] args) {
        if (args == null || args.length == 0) {
            throw new UsageException("--baseline <directory> and --candidate <directory> are required");
        }
        Path baseline = null;
        Path candidate = null;
        Path output = null;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-h", "--help" -> help = true;
                case "--baseline" -> baseline = Path.of(value(args, ++i, "--baseline"));
                case "--candidate" -> candidate = Path.of(value(args, ++i, "--candidate"));
                case "--output" -> output = Path.of(value(args, ++i, "--output"));
                default -> throw new UsageException("Unknown argument: " + args[i]);
            }
        }
        if (help) {
            return new Arguments(true, null, null, null);
        }
        if (baseline == null) {
            throw new UsageException("Missing required --baseline <directory>");
        }
        if (candidate == null) {
            throw new UsageException("Missing required --candidate <directory>");
        }
        return new Arguments(false, baseline, candidate, output);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new UsageException("Missing value for " + option);
        }
        return args[index];
    }

    static void printHelp(PrintStream out) {
        out.println("Usage:");
        out.println("  compare-evaluations --baseline <directory> --candidate <directory> [--output <directory>]");
        out.println();
        out.println("Compare two existing Evaluation Kit run directories without rerunning detectors.");
        out.println("Writes comparison.json and a self-contained comparison.html report.");
        out.println();
        out.println("Options:");
        out.println("  --baseline <directory>   Baseline evaluation run directory.");
        out.println("  --candidate <directory>  Candidate evaluation run directory.");
        out.println("  --output <directory>     Optional new output directory (temporary if omitted).");
        out.println("  -h, --help               Show this help and exit.");
        out.println();
        out.println("Exit codes: 0 success/help, 1 comparison/IO failure, 2 invalid usage.");
        out.println("Reports contain factual deltas only; they are not deployment decisions.");
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup of temporary output.
        }
    }

    private record Arguments(boolean help, Path baseline, Path candidate, Path output) {
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }
}
