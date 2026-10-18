package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.replay.ReplayConfiguration;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Package-private orchestration for the Evaluation Kit evaluation command-line entry.
 * Supports generated-corpus ({@code --corpus}) and evaluator-provided ({@code --dataset}) modes.
 */
final class GeneratedCorpusEvaluationCli {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_USAGE = 2;

    private static final double DEFAULT_THRESHOLD = 0.5d;

    private GeneratedCorpusEvaluationCli() {
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        try {
            ParsedArguments parsed = parse(args);
            if (parsed.helpRequested()) {
                printHelp(out);
                return EXIT_SUCCESS;
            }
            return evaluate(parsed, out, err);
        } catch (UsageException usage) {
            err.println(usage.getMessage());
            err.println();
            printHelp(err);
            return EXIT_USAGE;
        } catch (GeneratedCorpusEvaluationException evaluation) {
            err.println(formatFailure(evaluation.failureKind(), evaluation.getMessage()));
            return EXIT_FAILURE;
        } catch (IOException io) {
            err.println(formatFailure(GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE, io.getMessage()));
            return EXIT_FAILURE;
        } catch (RuntimeException unexpected) {
            String message = unexpected.getMessage() == null || unexpected.getMessage().isBlank()
                ? unexpected.getClass().getSimpleName()
                : unexpected.getMessage();
            err.println(formatFailure(GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE, message));
            return EXIT_FAILURE;
        }
    }

    private static int evaluate(ParsedArguments parsed, PrintStream out, PrintStream err) throws IOException {
        Path inputDirectory = parsed.inputDirectory().toAbsolutePath().normalize();
        if (!Files.exists(inputDirectory)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT,
                (parsed.datasetMode() ? "Dataset" : "Corpus") + " directory does not exist: " + inputDirectory);
        }
        if (!Files.isDirectory(inputDirectory)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT,
                (parsed.datasetMode() ? "Dataset" : "Corpus") + " path must be a directory: " + inputDirectory);
        }

        Path outputDirectory = parsed.outputDirectory();
        boolean temporaryOutput = false;
        if (outputDirectory == null) {
            String prefix = parsed.datasetMode()
                ? "ai-sentinel-evaluator-dataset-eval-"
                : "ai-sentinel-generated-corpus-eval-";
            outputDirectory = Files.createTempDirectory(prefix);
            Files.delete(outputDirectory);
            temporaryOutput = true;
        } else {
            outputDirectory = outputDirectory.toAbsolutePath().normalize();
            rejectProtectedEvidenceDestination(outputDirectory);
            if (Files.exists(outputDirectory)) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE,
                    "Evidence output directory already exists: " + outputDirectory);
            }
            Path parent = outputDirectory.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        }

        try {
            if (parsed.datasetMode()) {
                EvaluatorProvidedDatasetEvaluationResult result =
                    new EvaluatorProvidedDatasetEvaluator().evaluate(
                        inputDirectory,
                        ReplayConfiguration.referenceDefaults(),
                        new DetectionClassificationConfiguration(parsed.anomalyThreshold()),
                        outputDirectory
                    );
                out.print(EvaluatorProvidedEvaluationSummaryFormatter.format(
                    result, outputDirectory, temporaryOutput));
            } else {
                GeneratedCorpusEvaluationResult result = new GeneratedCorpusDetectionEvaluator().evaluate(
                    inputDirectory,
                    ReplayConfiguration.referenceDefaults(),
                    new DetectionClassificationConfiguration(parsed.anomalyThreshold()),
                    outputDirectory
                );
                out.print(GeneratedCorpusEvaluationSummaryFormatter.format(
                    result, outputDirectory, temporaryOutput));
            }
            return EXIT_SUCCESS;
        } finally {
            if (temporaryOutput) {
                deleteRecursively(outputDirectory);
            }
        }
    }

    private static void rejectProtectedEvidenceDestination(Path outputDirectory) {
        if (CandidateDetectionEvaluationRunner.isProtectedEvidenceDestination(outputDirectory)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE,
                "Evidence output must not target protected accepted/reference evidence locations: "
                    + outputDirectory);
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Best-effort temp cleanup; leaving a stray temp directory is not a user-facing failure.
        }
    }

    private static ParsedArguments parse(String[] args) {
        if (args == null || args.length == 0) {
            throw new UsageException("Exactly one of --corpus <directory> or --dataset <directory> is required");
        }

        boolean help = false;
        Path corpus = null;
        Path dataset = null;
        Path output = null;
        Double threshold = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equals(arg) || "-h".equals(arg)) {
                help = true;
                continue;
            }
            if ("--corpus".equals(arg)) {
                corpus = Path.of(requireValue(args, ++i, "--corpus"));
                continue;
            }
            if ("--dataset".equals(arg)) {
                dataset = Path.of(requireValue(args, ++i, "--dataset"));
                continue;
            }
            if ("--output".equals(arg)) {
                output = Path.of(requireValue(args, ++i, "--output"));
                continue;
            }
            if ("--threshold".equals(arg)) {
                threshold = parseThreshold(requireValue(args, ++i, "--threshold"));
                continue;
            }
            throw new UsageException("Unknown argument: " + arg);
        }

        if (help) {
            return ParsedArguments.help();
        }
        if (corpus != null && dataset != null) {
            throw new UsageException("--corpus and --dataset are mutually exclusive; provide exactly one");
        }
        if (corpus == null && dataset == null) {
            throw new UsageException("Exactly one of --corpus <directory> or --dataset <directory> is required");
        }
        double resolvedThreshold = threshold == null ? DEFAULT_THRESHOLD : threshold;
        if (dataset != null) {
            return new ParsedArguments(false, true, dataset, output, resolvedThreshold);
        }
        return new ParsedArguments(false, false, corpus, output, resolvedThreshold);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new UsageException("Missing value for " + option);
        }
        return args[index];
    }

    private static double parseThreshold(String raw) {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
                throw new UsageException("--threshold must be a finite number in [0,1]");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new UsageException("--threshold must be a finite number in [0,1]");
        }
    }

    private static String formatFailure(GeneratedCorpusEvaluationFailureKind kind, String message) {
        String safeMessage = message == null || message.isBlank() ? "Evaluation failed" : message;
        return "ERROR [" + kind.name() + "]: " + safeMessage;
    }

    static void printHelp(PrintStream stream) {
        stream.println("Usage:");
        stream.println("  evaluate-generated-corpus --corpus <directory> [--output <directory>] [--threshold <0..1>]");
        stream.println("  evaluate-generated-corpus --dataset <directory> [--output <directory>] [--threshold <0..1>]");
        stream.println();
        stream.println("Evaluate one Evaluation Kit input directory. Exactly one of --corpus or --dataset is");
        stream.println("required. Does not require writing Java or assembling Maven modules.");
        stream.println();
        stream.println("Modes:");
        stream.println("  --corpus   Generated Evaluation Kit corpus (corpus-manifest + replay manifest +");
        stream.println("             events + annotations). Controlled reference-corpus evaluation.");
        stream.println("  --dataset  Evaluator-provided (BYO) dataset (dataset-manifest + events + optional");
        stream.println("             annotations). Does not use generator seed/build provenance.");
        stream.println();
        stream.println("Options:");
        stream.println("  --corpus <directory>     Generated corpus directory (mutually exclusive with --dataset).");
        stream.println("                           Relative paths resolve against your current directory.");
        stream.println("  --dataset <directory>    Evaluator-provided dataset directory (mutually exclusive with");
        stream.println("                           --corpus). Relative paths resolve against your current directory.");
        stream.println("  --output <directory>     Optional. Evidence + report output directory (temp if omitted).");
        stream.println("                           Writes kit-evaluation-result.json, event-inspection.json,");
        stream.println("                           evaluation-report.html, and specialized detection evidence.");
        stream.println("  --threshold <0..1>       Optional. Anomaly classification threshold (default 0.5).");
        stream.println("  -h, --help               Show this help and exit.");
        stream.println();
        stream.println("Exit codes:");
        stream.println("  0  Evaluation succeeded");
        stream.println("  1  Load / integrity / ground-truth / evaluation failure");
        stream.println("  2  Invalid usage");
        stream.println();
        stream.println("Evidence boundaries:");
        stream.println("  Synthetic / BYO evaluation is not production validation.");
        stream.println("  Evaluation is not deployment.");
        stream.println("  Ground truth is not detector input.");
        stream.println("  Undefined metrics print as unavailable (not fabricated zeros).");
    }

    private record ParsedArguments(
        boolean helpRequested,
        boolean datasetMode,
        Path inputDirectory,
        Path outputDirectory,
        double anomalyThreshold
    ) {
        static ParsedArguments help() {
            return new ParsedArguments(true, false, null, null, DEFAULT_THRESHOLD);
        }
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    /**
     * Locale-independent formatting helpers for tests and summary text.
     */
    static String formatRatio(DetectionMetricValue value) {
        if (value == null || !value.defined()) {
            return "unavailable";
        }
        return String.format(Locale.ROOT, "%.6f", value.value());
    }
}
