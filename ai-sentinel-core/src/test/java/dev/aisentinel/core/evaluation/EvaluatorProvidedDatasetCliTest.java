package dev.aisentinel.core.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluatorProvidedDatasetCliTest {

    @TempDir
    Path tempDir;

    @Test
    void datasetModeEvaluatesLabeledFixture() {
        Path output = tempDir.resolve("cli-labeled");
        Capture capture = run(
            "--dataset", fixture("minimal-labeled").toString(),
            "--output", output.toString()
        );
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        assertThat(capture.stdout()).contains("Evaluator-provided dataset evaluation");
        assertThat(capture.stdout()).contains("datasetSource: evaluator-provided");
        assertThat(capture.stdout()).contains("datasetId: byo.minimal.labeled.001");
        assertThat(capture.stdout()).doesNotContain("seed:");
        assertThat(capture.stdout()).doesNotContain("generatorBuildId");
        assertThat(Files.isRegularFile(output.resolve("kit-evaluation-result.json"))).isTrue();
    }

    @Test
    void datasetModeEvaluatesUnlabeledFixture() {
        Path output = tempDir.resolve("cli-unlabeled");
        Capture capture = run(
            "--dataset", fixture("minimal-unlabeled").toString(),
            "--output", output.toString()
        );
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_SUCCESS);
        assertThat(capture.stdout()).contains("labeled: false");
        assertThat(capture.stdout()).contains("unknownOrUnlabeledEvents: 4");
        assertThat(capture.stdout()).contains("unavailable");
    }

    @Test
    void corpusAndDatasetTogetherAreUsageFailure() {
        Capture capture = run(
            "--corpus", tempDir.resolve("c").toString(),
            "--dataset", tempDir.resolve("d").toString()
        );
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_USAGE);
        assertThat(capture.stderr()).contains("mutually exclusive");
    }

    @Test
    void missingDatasetDirectoryFailsClearly() {
        Path missing = tempDir.resolve("missing-byo");
        Capture capture = run("--dataset", missing.toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_FAILURE);
        assertThat(capture.stderr()).contains("MISSING_ARTIFACT");
        assertThat(capture.stderr()).contains("Dataset directory does not exist");
    }

    @Test
    void validationFailureSurfacesIntegrityKind() throws Exception {
        Path copy = tempDir.resolve("tampered");
        Files.createDirectories(copy);
        Path source = fixture("minimal-unlabeled");
        Files.copy(source.resolve("dataset-manifest.json"), copy.resolve("dataset-manifest.json"));
        Files.writeString(copy.resolve("events.jsonl"), "not-json\n");
        Capture capture = run("--dataset", copy.toString(), "--output", tempDir.resolve("bad-out").toString());
        assertThat(capture.exitCode()).isEqualTo(GeneratedCorpusEvaluationCli.EXIT_FAILURE);
        assertThat(capture.stderr()).containsPattern("INTEGRITY_FAILURE|UNSUPPORTED_SCHEMA|EVALUATION_FAILURE");
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

    private static Path fixture(String name) {
        Path resource = Path.of("src/test/resources/evaluation-kit-byo").resolve(name);
        if (Files.isDirectory(resource)) {
            return resource.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Missing BYO fixture: " + resource.toAbsolutePath());
    }

    private record Capture(int exitCode, String stdout, String stderr) {
    }
}
