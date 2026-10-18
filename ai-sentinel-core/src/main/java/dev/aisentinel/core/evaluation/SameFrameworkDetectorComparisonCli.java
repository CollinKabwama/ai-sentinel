package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.replay.ReplayConfiguration;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Package-private Level-3 same-framework detector comparison orchestration.
 * <p>
 * Runs the statistical reference Evaluation Kit path and the offline Isolation Forest
 * reference path on the same three kit-reference corpora, then reuses
 * {@link EvaluationComparisonEngine} for factual deltas only (no winner language).
 */
final class SameFrameworkDetectorComparisonCli {

    static final int EXIT_SUCCESS = 0;
    static final int EXIT_FAILURE = 1;
    static final int EXIT_USAGE = 2;

    static final String STATISTICAL_DIR = "statistical";
    static final String ISOLATION_FOREST_DIR = "isolation-forest-reference";
    static final String COMPARISON_DIR = "comparison";

    private static final List<Target> TARGETS = List.of(
        new Target("established-normal", "evaluation/kit-reference/corpora/kit.established-normal"),
        new Target("abrupt-burst", "evaluation/kit-reference/corpora/kit.abrupt-burst"),
        new Target("recovery", "evaluation/kit-reference/corpora/kit.recovery")
    );

    private SameFrameworkDetectorComparisonCli() {
    }

    static int run(String[] args, PrintStream out, PrintStream err, Path repositoryRoot) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        try {
            ParsedArguments parsed = parse(args);
            if (parsed.helpRequested()) {
                printHelp(out);
                return EXIT_SUCCESS;
            }
            return compare(parsed, out, repositoryRoot);
        } catch (UsageException usage) {
            err.println(usage.getMessage());
            err.println();
            printHelp(err);
            return EXIT_USAGE;
        } catch (GeneratedCorpusEvaluationException evaluation) {
            err.println("ERROR [" + evaluation.failureKind() + "]: " + evaluation.getMessage());
            return EXIT_FAILURE;
        } catch (IOException io) {
            err.println("ERROR [EVALUATION_FAILURE]: " + io.getMessage());
            return EXIT_FAILURE;
        } catch (RuntimeException unexpected) {
            String message = unexpected.getMessage() == null || unexpected.getMessage().isBlank()
                ? unexpected.getClass().getSimpleName()
                : unexpected.getMessage();
            err.println("ERROR [EVALUATION_FAILURE]: " + message);
            return EXIT_FAILURE;
        }
    }

    private static int compare(ParsedArguments parsed, PrintStream out, Path repositoryRoot)
        throws IOException {
        Path outputRoot = parsed.outputDirectory().toAbsolutePath().normalize();
        if (Files.exists(outputRoot)) {
            throw new GeneratedCorpusEvaluationException(
                GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE,
                "Output directory already exists: " + outputRoot);
        }
        Path parent = outputRoot.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.createDirectories(outputRoot);

        ReferenceIsolationForestConfig ifConfig = ReferenceIsolationForestConfig.FROZEN;
        DetectionClassificationConfiguration statisticalClassification =
            new DetectionClassificationConfiguration(0.5d);
        DetectionClassificationConfiguration ifClassification =
            new DetectionClassificationConfiguration(ifConfig.anomalyThreshold());
        ReplayConfiguration statisticalReplay = ReplayConfiguration.referenceDefaults();

        out.println("AI-Sentinel same-framework detector comparison (Level-3)");
        out.println("Targets: " + TARGETS.size());
        out.println("Statistical threshold: 0.5");
        out.println("Isolation Forest threshold: " + format(ifConfig.anomalyThreshold()));
        out.println("Isolation Forest config: trees=" + ifConfig.numTrees()
            + " depth=" + ifConfig.maxDepth()
            + " seed=" + ifConfig.randomSeed()
            + " minTrainingSamples=" + ifConfig.minTrainingSamples());
        out.println("Same-origin: both detectors are implemented in this repository.");
        out.println();

        for (Target target : TARGETS) {
            Path corpusDir = repositoryRoot.resolve(target.corpusRelativePath()).normalize();
            if (!Files.isDirectory(corpusDir)) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT,
                    "Corpus directory not found: " + corpusDir);
            }

            Path targetOut = outputRoot.resolve(target.targetId());
            Path statisticalOut = targetOut.resolve(STATISTICAL_DIR);
            Path ifOut = targetOut.resolve(ISOLATION_FOREST_DIR);
            Path comparisonOut = targetOut.resolve(COMPARISON_DIR);

            out.println("Target: " + target.targetId());
            out.println("  corpus: " + target.corpusRelativePath());

            GeneratedCorpusEvaluationResult statisticalResult =
                new GeneratedCorpusDetectionEvaluator().evaluate(
                    corpusDir,
                    statisticalReplay,
                    statisticalClassification,
                    statisticalOut
                );
            String statisticalRunId = evaluationRunId(statisticalResult);
            out.println("  statistical evaluationRunId: " + statisticalRunId);

            ReferenceIsolationForestScorer ifScorer = new ReferenceIsolationForestScorer(ifConfig);
            GeneratedCorpusEvaluationResult ifResult =
                ReferenceIsolationForestReplayBindings.evaluator(ifScorer).evaluate(
                    corpusDir,
                    ReferenceIsolationForestReplayBindings.configuration(ifConfig),
                    ifClassification,
                    ifOut
                );
            String ifRunId = evaluationRunId(ifResult);
            out.println("  isolation-forest-reference evaluationRunId: " + ifRunId);
            if (statisticalRunId.equals(ifRunId)) {
                throw new GeneratedCorpusEvaluationException(
                    GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE,
                    "statistical and Isolation Forest evaluationRunId must differ for "
                        + target.targetId());
            }

            EvaluationComparisonResult comparison = new EvaluationComparisonEngine().compare(
                statisticalOut,
                ifOut,
                comparisonOut
            );
            out.println("  comparison: eventsCompared=" + comparison.eventsCompared()
                + " eventsWithChanges=" + comparison.eventsWithChanges()
                + " newFalsePositives=" + comparison.newFalsePositives()
                + " newFalseNegatives=" + comparison.newFalseNegatives());
            out.println("  comparisonJson: " + comparison.comparisonJson());
            out.println("  comparisonHtml: " + comparison.comparisonHtml());
            out.println();
        }

        out.println("Meaning:");
        out.println("  Same controlled corpora evaluated under two same-origin detectors;");
        out.println("  factual metric and prediction deltas recorded.");
        out.println("Not established:");
        out.println("  detector superiority, independent third-party benchmarking,");
        out.println("  production efficacy, or deployment readiness.");
        return EXIT_SUCCESS;
    }

    private static ParsedArguments parse(String[] args) {
        if (args == null || args.length == 0) {
            throw new UsageException("--output <directory> is required");
        }
        Path output = null;
        boolean help = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("-h".equals(arg) || "--help".equals(arg)) {
                help = true;
                continue;
            }
            if ("--output".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].startsWith("-")) {
                    throw new UsageException("--output requires a directory path");
                }
                output = Path.of(args[++i]);
                continue;
            }
            throw new UsageException("Unknown argument: " + arg);
        }
        if (help) {
            return new ParsedArguments(true, null);
        }
        if (output == null) {
            throw new UsageException("--output <directory> is required");
        }
        return new ParsedArguments(false, output);
    }

    private static void printHelp(PrintStream stream) {
        stream.println("Usage:");
        stream.println("  SameFrameworkDetectorComparisonMain --output <new-directory>");
        stream.println();
        stream.println("Compares the statistical Evaluation Kit path against the offline");
        stream.println("Isolation Forest reference scorer on three kit-reference corpora.");
        stream.println("Factual deltas only — not a winner or ranking.");
        stream.println();
        stream.println("Options:");
        stream.println("  --output <directory>   Required. Must not already exist.");
        stream.println("  -h, --help             Show this help.");
    }

    private static String evaluationRunId(GeneratedCorpusEvaluationResult result) {
        GeneratedCorpusProvenance provenance = result.provenance();
        DetectionEvaluationEvidence.ReplayProvenance replay = result.detectionRun().evidence().replay();
        return EvaluationRunIdentity.evaluationRunId(
            "generated-corpus",
            provenance.corpusId(),
            provenance.eventsSha256(),
            provenance.annotationsSha256(),
            provenance.featureSchemaVersion(),
            provenance.evaluationEventSchemaVersion(),
            replay.configurationFingerprint(),
            replay.scorerId(),
            replay.scorerVersion(),
            replay.policyId(),
            replay.policyVersion(),
            result.detectionRun().metrics().classification().anomalyThreshold(),
            "1"
        );
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private record Target(String targetId, String corpusRelativePath) {
    }

    private record ParsedArguments(boolean helpRequested, Path outputDirectory) {
    }

    private static final class UsageException extends RuntimeException {
        private UsageException(String message) {
            super(message);
        }
    }
}
