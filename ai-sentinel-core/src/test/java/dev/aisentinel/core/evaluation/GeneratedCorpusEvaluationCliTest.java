package dev.aisentinel.core.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedCorpusEvaluationCliTest {

    @TempDir
    Path tempDir;

    @Test
    void helpSucceedsWithExitZero() {
        Capture capture = run("--help");
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        assertThat(capture.stdout()).contains("Usage:");
        assertThat(capture.stdout()).contains("--corpus");
        assertThat(capture.stdout()).contains("--dataset");
        assertThat(capture.stdout()).contains("mutually exclusive");
        assertThat(capture.stdout()).contains("Synthetic / BYO evaluation is not production validation");
        assertThat(capture.stdout()).doesNotContain("NaN");
        assertThat(capture.stdout()).doesNotContain("Infinity");
        assertNoInternalMetadata(capture.stdout());
    }

    @Test
    void missingCorpusOptionIsUsageFailure() {
        Capture capture = run();
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_USAGE);
        assertThat(capture.stderr()).contains("Exactly one of --corpus");
        assertThat(capture.stderr()).contains("--dataset");
        assertThat(capture.stderr()).contains("Usage:");
    }

    @Test
    void corpusAndDatasetMutualExclusionIsUsageFailure() {
        Capture capture = run(
            "--corpus", corpus("kit.established-normal").toString(),
            "--dataset", tempDir.resolve("byo").toString()
        );
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_USAGE);
        assertThat(capture.stderr()).contains("mutually exclusive");
    }

    @Test
    void unknownArgumentIsUsageFailure() {
        Capture capture = run("--corpus", corpus("kit.established-normal").toString(), "--weird");
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_USAGE);
        assertThat(capture.stderr()).contains("Unknown argument: --weird");
    }

    @Test
    void missingCorpusDirectoryFailsClearly() {
        Path missing = tempDir.resolve("does-not-exist");
        Capture capture = run("--corpus", missing.toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_FAILURE);
        assertThat(capture.stderr()).contains("MISSING_ARTIFACT");
        assertThat(capture.stderr()).contains("Corpus directory does not exist");
    }

    @Test
    void fileInsteadOfDirectoryFailsClearly() throws Exception {
        Path file = tempDir.resolve("not-a-directory.txt");
        Files.writeString(file, "not a corpus");
        Capture capture = run("--corpus", file.toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_FAILURE);
        assertThat(capture.stderr()).contains("MISSING_ARTIFACT");
        assertThat(capture.stderr()).contains("must be a directory");
    }

    @Test
    void relativeCorpusPathEvaluatesSuccessfully() {
        // Surefire runs with module cwd (ai-sentinel-core); use a relative path from there.
        Path relative = Path.of("..", "evaluation", "kit-reference", "corpora", "kit.established-normal");
        Path output = tempDir.resolve("relative-out");
        Capture capture = run(
            "--corpus", relative.toString(),
            "--output", output.toString()
        );
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        assertThat(capture.stdout()).contains("Status: SUCCESS");
        assertThat(capture.stdout()).contains("warmupEvents: 4");
        assertThat(capture.stdout()).contains("labeledBenignEvents: 6");
        assertThat(capture.stdout()).contains("labeledAnomalousEvents: 0");
        assertThat(capture.stdout()).contains("unavailable");
        assertThat(Files.isDirectory(output)).isTrue();
    }

    @Test
    void absoluteCorpusPathEvaluatesSuccessfully() {
        Path absolute = corpus("kit.abrupt-burst").toAbsolutePath().normalize();
        Path output = tempDir.resolve("absolute-out");
        Capture capture = run(
            "--corpus", absolute.toString(),
            "--output", output.toString()
        );
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        assertThat(capture.stdout()).contains("Status: SUCCESS");
        assertThat(capture.stdout()).contains("eventCount: 10");
        assertThat(capture.stdout()).contains("warmupEvents: 4");
        assertThat(capture.stdout()).contains("labeledBenignEvents: 2");
        assertThat(capture.stdout()).contains("labeledAnomalousEvents: 4");
        assertThat(capture.stdout()).contains("generatorBuildId: aisentinel-kit-reference-corpus@1");
        assertThat(capture.stdout()).doesNotContain("NaN");
        assertThat(capture.stdout()).doesNotContain("Infinity");
        assertNoInternalMetadata(capture.stdout());
    }

    @Test
    void unknownLabelsPrintAsUnknownNotBenign() {
        Path output = tempDir.resolve("unknown-out");
        Capture capture = run(
            "--corpus", corpus("kit.invalid-score-degradation").toString(),
            "--output", output.toString()
        );
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        assertThat(capture.stdout()).contains("unknownOrUnlabeledEvents: 4");
        assertThat(capture.stdout()).contains("labeledBenignEvents: 2");
        assertThat(capture.stdout()).contains("labeledAnomalousEvents: 0");
        assertThat(capture.stdout()).doesNotContain("INVALID_SCORE detected");
        assertThat(capture.stdout()).contains("unavailable");
    }

    @Test
    void missingManifestFailsWithIntegrityOrMissingArtifact() throws Exception {
        Path copy = copyCorpus("kit.established-normal");
        Files.delete(copy.resolve("corpus-manifest.json"));
        Capture capture = run("--corpus", copy.toString(), "--output", tempDir.resolve("missing-manifest").toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_FAILURE);
        assertThat(capture.stderr()).containsPattern("MISSING_ARTIFACT|INTEGRITY_FAILURE");
    }

    @Test
    void checksumTamperingFailsWithIntegrityFailure() throws Exception {
        Path copy = copyCorpus("kit.established-normal");
        Path events = copy.resolve("events.jsonl");
        Files.writeString(events, Files.readString(events) + "\n");
        Capture capture = run("--corpus", copy.toString(), "--output", tempDir.resolve("tampered").toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_FAILURE);
        assertThat(capture.stderr()).contains("INTEGRITY_FAILURE");
    }

    @Test
    void groundTruthPhantomEventFails() throws Exception {
        Path copy = copyCorpus("kit.established-normal");
        Path annotations = copy.resolve("annotations.json");
        String text = Files.readString(annotations);
        String injected = text.replaceFirst(
            "\"annotations\":\\[",
            "\"annotations\":[{\"eventId\":\"phantom-event\",\"scenarioId\":\"kit.established-normal.v1\","
                + "\"expectedClass\":\"benign\",\"category\":\"evaluation-normal\"},"
        );
        Files.writeString(annotations, injected);
        // Keep annotations checksum valid so the failure is ground-truth join, not integrity.
        rewriteAnnotationsChecksum(copy);
        Capture capture = run("--corpus", copy.toString(), "--output", tempDir.resolve("phantom").toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_FAILURE);
        assertThat(capture.stderr()).contains("GROUND_TRUTH_FAILURE");
    }

    @Test
    void unsupportedSchemaFailsClearly() throws Exception {
        Path copy = copyCorpus("kit.established-normal");
        Path manifest = copy.resolve("corpus-manifest.json");
        String text = Files.readString(manifest);
        Files.writeString(
            manifest,
            text.replaceFirst("\"corpusSchemaVersion\":\"[^\"]+\"", "\"corpusSchemaVersion\":\"999\"")
        );
        Capture capture = run("--corpus", copy.toString(), "--output", tempDir.resolve("bad-schema").toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_FAILURE);
        assertThat(capture.stderr()).contains("UNSUPPORTED_SCHEMA");
    }

    @Test
    void outputIsDeterministicForSameCorpusAndThreshold() {
        Path corpus = corpus("kit.legitimate-burst");
        Path out1 = tempDir.resolve("det-1");
        Path out2 = tempDir.resolve("det-2");
        Capture first = run("--corpus", corpus.toString(), "--output", out1.toString(), "--threshold", "0.5");
        Capture second = run("--corpus", corpus.toString(), "--output", out2.toString(), "--threshold", "0.5");
        assertThat(first.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        assertThat(second.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        String normalizedFirst = stripEvidenceDirectory(first.stdout());
        String normalizedSecond = stripEvidenceDirectory(second.stdout());
        assertThat(normalizedFirst).isEqualTo(normalizedSecond);
    }

    @Test
    void undefinedMetricsNeverPrintAsFabricatedZero() {
        Path output = tempDir.resolve("benign-only");
        Capture capture = run(
            "--corpus", corpus("kit.established-normal").toString(),
            "--output", output.toString()
        );
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        // Benign-only corpora leave positive-class ratios undefined.
        assertThat(capture.stdout()).contains("precision: unavailable");
        assertThat(capture.stdout()).contains("recall: unavailable");
        assertThat(capture.stdout()).doesNotContain("precision: 0.000000");
        assertThat(capture.stdout()).doesNotContain("recall: 0.000000");
        assertThat(capture.stdout()).doesNotContain("NaN");
        assertThat(capture.stdout()).doesNotContain("Infinity");
    }

    @Test
    void omittedOutputLeavesNoTemporaryEvidenceDirectoryOnSuccess() throws Exception {
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));
        Capture capture = run("--corpus", corpus("kit.abrupt-burst").toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        try (var stream = Files.list(tmpRoot)) {
            assertThat(stream
                .map(p -> p.getFileName().toString())
                .filter(name -> name.startsWith("ai-sentinel-generated-corpus-eval-")))
                .isEmpty();
        }
    }

    @Test
    void omittedOutputLeavesNoTemporaryEvidenceDirectoryOnFailure() throws Exception {
        Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));
        Capture capture = run("--corpus", tempDir.resolve("missing-for-leak-check").toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_FAILURE);
        try (var stream = Files.list(tmpRoot)) {
            assertThat(stream
                .map(p -> p.getFileName().toString())
                .filter(name -> name.startsWith("ai-sentinel-generated-corpus-eval-")))
                .isEmpty();
        }
    }

    @Test
    void refusesProtectedAcceptedEvidenceDestinations() {
        Path corpus = corpus("kit.established-normal");
        Path protectedOut = repoRoot().resolve("evaluation/detection-reference-baseline/generated-run");
        Capture capture = run("--corpus", corpus.toString(), "--output", protectedOut.toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_FAILURE);
        assertThat(capture.stderr()).contains("protected accepted/reference evidence");
    }

    @Test
    void protectedEvidenceDestinationGuardHandlesNormalizationAndNearPrefixes() {
        Path root = repoRoot();
        assertThat(CandidateDetectionEvaluationRunner.isProtectedEvidenceDestination(root.resolve("evaluation/reference")))
            .isTrue();
        assertThat(CandidateDetectionEvaluationRunner.isProtectedEvidenceDestination(
            root.resolve("evaluation/reference/generated-run"))).isTrue();
        assertThat(CandidateDetectionEvaluationRunner.isProtectedEvidenceDestination(
            root.resolve("evaluation/reference/../reference/generated-run")))
            .isTrue();
        assertThat(CandidateDetectionEvaluationRunner.isProtectedEvidenceDestination(
            root.resolve("evaluation/reference-copy/generated-run")))
            .isFalse();
    }

    @Test
    void protectedEvidenceDestinationGuardFollowsExistingSymlinkParent() throws Exception {
        Path link = tempDir.resolve("reference-link");
        try {
            Files.createSymbolicLink(link, repoRoot().resolve("evaluation/reference"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links unavailable: " + e.getMessage());
        }
        assertThat(CandidateDetectionEvaluationRunner.isProtectedEvidenceDestination(link.resolve("generated-run")))
            .isTrue();
    }

    @Test
    void anomalyThresholdIsShownAsRunConfigurationNotCorpusProvenance() {
        Path output = tempDir.resolve("provenance-vs-config");
        Capture capture = run(
            "--corpus", corpus("kit.abrupt-burst").toString(),
            "--output", output.toString(),
            "--threshold", "0.5"
        );
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        String stdout = capture.stdout();
        assertThat(stdout).contains("Provenance (corpus)");
        assertThat(stdout).contains("Evaluation configuration (this run only, not corpus provenance)");
        int provenanceHeader = stdout.indexOf("Provenance (corpus)");
        int configHeader = stdout.indexOf("Evaluation configuration");
        int thresholdLine = stdout.indexOf("anomalyThreshold:");
        assertThat(thresholdLine).isGreaterThan(configHeader);
        assertThat(configHeader).isGreaterThan(provenanceHeader);
        // anomalyThreshold must not appear before the configuration header (i.e. not grouped
        // under corpus provenance).
        String provenanceBlock = stdout.substring(provenanceHeader, configHeader);
        assertThat(provenanceBlock).doesNotContain("anomalyThreshold");
    }

    @Test
    void endToEndEntryUsesGeneratedCorpusDetectionEvaluatorPath() throws Exception {
        Path output = tempDir.resolve("e2e");
        Capture capture = run(
            "--corpus", corpus("kit.recovery").toString(),
            "--output", output.toString()
        );
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        assertThat(capture.stdout()).contains("Status: SUCCESS");
        assertThat(capture.stdout()).contains("eventCount: 10");
        assertThat(capture.stdout()).contains("warmupEvents: 4");
        assertThat(capture.stdout()).contains("labeledBenignEvents: 3");
        assertThat(capture.stdout()).contains("labeledAnomalousEvents: 3");
        assertThat(capture.stdout()).contains("Controlled synthetic reference-evaluation observations");
        assertThat(capture.stdout()).contains("kitResult: kit-evaluation-result.json");
        assertThat(capture.stdout()).contains("eventInspection: event-inspection.json");
        assertThat(capture.stdout()).contains("htmlReport: evaluation-report.html");
        assertThat(Files.isRegularFile(output.resolve("kit-evaluation-result.json"))).isTrue();
        assertThat(Files.isRegularFile(output.resolve("event-inspection.json"))).isTrue();
        assertThat(Files.isRegularFile(output.resolve("evaluation-report.html"))).isTrue();
        assertThat(Files.isRegularFile(output.resolve("evaluation.json"))).isTrue();
        try (var stream = Files.list(output)) {
            assertThat(stream.findAny()).isPresent();
        }
    }

    @ParameterizedTest
    @MethodSource("allCheckedInCorpora")
    void allCheckedInCorporaSucceedThroughCli(Path corpusDir) {
        Path output = tempDir.resolve(corpusDir.getFileName().toString() + "-cli");
        Capture capture = run(
            "--corpus", corpusDir.toString(),
            "--output", output.toString()
        );
        assertThat(capture.exitCode())
            .as(corpusDir.getFileName().toString())
            .isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        assertThat(capture.stdout()).contains("Status: SUCCESS");
        assertThat(capture.stdout()).contains("warmupEvents:");
        assertThat(capture.stdout()).doesNotContain("NaN");
        assertThat(capture.stdout()).doesNotContain("Infinity");
        assertNoInternalMetadata(capture.stdout());
    }

    static Stream<Path> allCheckedInCorpora() throws Exception {
        Path root = repoRoot().resolve("evaluation/kit-reference/corpora");
        try (var stream = Files.list(root)) {
            List<Path> corpora = stream.filter(Files::isDirectory).sorted().toList();
            assertThat(corpora).hasSize(11);
            return corpora.stream();
        }
    }

    private Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = GeneratedCorpusEvaluationCli.run(
            args,
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(
            code,
            stdout.toString(StandardCharsets.UTF_8),
            stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private static Path corpus(String name) {
        return repoRoot().resolve("evaluation/kit-reference/corpora").resolve(name);
    }

    private Path copyCorpus(String name) throws Exception {
        Path source = corpus(name);
        Path destination = tempDir.resolve("copy-" + name);
        Files.walk(source).forEach(path -> {
            try {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        return destination;
    }

    private static void rewriteAnnotationsChecksum(Path corpusDir) throws Exception {
        Path annotations = corpusDir.resolve("annotations.json");
        String sha = sha256Hex(Files.readAllBytes(annotations));
        Path manifest = corpusDir.resolve("corpus-manifest.json");
        String text = Files.readString(manifest);
        String updated = text.replaceFirst(
            "(\"annotationsArtifact\":\\{[^}]*\"sha256\":\")[0-9a-f]{64}",
            "$1" + sha
        );
        if (updated.equals(text)) {
            throw new IllegalStateException("Unable to rewrite annotationsArtifact checksum");
        }
        Files.writeString(manifest, updated);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String stripEvidenceDirectory(String stdout) {
        return stdout.replaceAll("(?m)^  directory: .*\\R?", "  directory: <normalized>\n");
    }

    private static void assertNoInternalMetadata(String text) {
        // Construct identifier prefixes by concatenation so this source file does not contain
        // those contiguous substrings as literals while still asserting they are absent from output.
        List<String> patterns = List.of(
            "ENG" + "-",
            "FIND" + "-",
            "TASK" + "-",
            "WAVE" + "-",
            "REVIEW" + "-",
            "REMEDI" + "ATION",
            "roadmap" + " task",
            "review" + " finding",
            "review" + " gate",
            "internal" + " task",
            "internal" + " tracker",
            "current" + " task",
            "next" + " task"
        );
        for (String pattern : patterns) {
            assertThat(text).doesNotContain(pattern);
        }
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        Path candidate = cwd.resolve("evaluation/kit-reference");
        if (Files.isDirectory(candidate)) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("evaluation/kit-reference"))) {
            return parent;
        }
        throw new IllegalStateException("Unable to locate repository root from " + cwd);
    }

    private record Capture(int exitCode, String stdout, String stderr) {
    }
}
