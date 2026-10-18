package dev.aisentinel.core.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationComparisonCliTest {
    @TempDir
    Path tempDir;

    @Test
    void helpReturnsZero() {
        Capture capture = run("--help");
        assertThat(capture.code()).isZero();
        assertThat(capture.out()).contains("compare-evaluations");
        assertThat(capture.err()).isEmpty();
    }

    @Test
    void noArgumentsReturnsUsage() {
        Capture capture = run();
        assertThat(capture.code()).isEqualTo(2);
        assertThat(capture.err()).contains("--baseline", "Usage:");
    }

    @Test
    void missingBaselineReturnsUsage() {
        Capture capture = run("--candidate", "candidate");
        assertThat(capture.code()).isEqualTo(2);
        assertThat(capture.err()).contains("Missing required --baseline");
    }

    @Test
    void successReturnsZeroWithCleanStderr() throws Exception {
        Runs runs = runs();
        Path output = tempDir.resolve("output");
        Capture capture = run("--baseline", runs.baseline().toString(),
            "--candidate", runs.candidate().toString(), "--output", output.toString());
        assertThat(capture.code()).isZero();
        assertThat(capture.out()).contains("eventsCompared: 1");
        assertThat(capture.err()).isEmpty();
        assertThat(output.resolve("comparison.json")).isRegularFile();
        assertThat(output.resolve("comparison.html")).isRegularFile();
    }

    @Test
    void incompatibleReturnsOne() throws Exception {
        Runs runs = runs();
        Path result = runs.candidate().resolve("kit-evaluation-result.json");
        Files.writeString(result, Files.readString(result)
            .replace("\"corpusId\": \"corpus.mini\"", "\"corpusId\": \"corpus.other\""));
        Path inspection = runs.candidate().resolve("event-inspection.json");
        Files.writeString(inspection, Files.readString(inspection)
            .replace("\"corpusId\": \"corpus.mini\"", "\"corpusId\": \"corpus.other\""));
        Capture capture = run("--baseline", runs.baseline().toString(),
            "--candidate", runs.candidate().toString(),
            "--output", tempDir.resolve("incompatible").toString());
        assertThat(capture.code()).isEqualTo(1);
        assertThat(capture.err()).contains("Incompatible evaluation runs");
    }

    @Test
    void existingOutputReturnsOne() throws Exception {
        Runs runs = runs();
        Path output = Files.createDirectory(tempDir.resolve("existing"));
        Capture capture = run("--baseline", runs.baseline().toString(),
            "--candidate", runs.candidate().toString(), "--output", output.toString());
        assertThat(capture.code()).isEqualTo(1);
        assertThat(capture.err()).contains("already exists");
    }

    private Capture run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = EvaluationComparisonCli.run(args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Capture(code, out.toString(StandardCharsets.UTF_8),
            err.toString(StandardCharsets.UTF_8));
    }

    private Runs runs() throws IOException {
        Path baseline = tempDir.resolve("baseline");
        Path candidate = tempDir.resolve("candidate");
        copy(Path.of("src/test/resources/evaluation-kit-comparison/baseline-identical"), baseline);
        copy(Path.of("src/test/resources/evaluation-kit-comparison/candidate-identical"), candidate);
        return new Runs(baseline, candidate);
    }

    private static void copy(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }

    private record Capture(int code, String out, String err) {
    }

    private record Runs(Path baseline, Path candidate) {
    }
}
