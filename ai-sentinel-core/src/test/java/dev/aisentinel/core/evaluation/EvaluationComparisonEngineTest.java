package dev.aisentinel.core.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationComparisonEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void identicalEvidenceHasZeroDeltasAndNoChangedEvents() throws Exception {
        EvaluationComparisonResult result = compareFresh("identical");
        String json = Files.readString(result.comparisonJson());
        assertThat(result.eventsWithChanges()).isZero();
        assertThat(json).contains("\"eventChanges\":[]", "\"delta\":0.000000");
    }

    @Test
    void countsTpToFnTransition() throws Exception {
        Runs runs = runs();
        replace(runs.candidate().resolve("event-inspection.json"),
            "\"predictedAnomalous\": true", "\"predictedAnomalous\": false");
        EvaluationComparisonResult result = compare(runs, "tpfn");
        assertThat(Files.readString(result.comparisonJson()))
            .contains("\"TP_TO_FN\":1", "\"newFalseNegatives\":1");
    }

    @Test
    void countsTnToFpTransition() throws Exception {
        Runs runs = runs();
        for (Path run : new Path[]{runs.baseline(), runs.candidate()}) {
            replace(run.resolve("event-inspection.json"),
                "\"expectedClass\": \"anomalous\"", "\"expectedClass\": \"benign\"");
        }
        replace(runs.baseline().resolve("event-inspection.json"),
            "\"predictedAnomalous\": true", "\"predictedAnomalous\": false");
        EvaluationComparisonResult result = compare(runs, "tnfp");
        assertThat(Files.readString(result.comparisonJson()))
            .contains("\"TN_TO_FP\":1", "\"newFalsePositives\":1");
    }

    @Test
    void recordsMetricBecomingAvailable() throws Exception {
        Runs runs = runs();
        Path result = runs.baseline().resolve("kit-evaluation-result.json");
        replaceDetectionFamily(result, "unavailable");
        EvaluationComparisonResult comparison = compare(runs, "availability");
        assertThat(Files.readString(comparison.comparisonJson()))
            .contains("\"availabilityChange\":\"became-available\"");
    }

    @Test
    void recordsScoreOnlyChange() throws Exception {
        Runs runs = runs();
        replace(runs.candidate().resolve("event-inspection.json"),
            "\"anomalyScore\": 0.9", "\"anomalyScore\": 0.8");
        String json = Files.readString(compare(runs, "score").comparisonJson());
        assertThat(json).contains("\"changes\":[\"score-changed\"]", "\"scoreDelta\":-0.100000");
    }

    @Test
    void recordsStatusSetChange() throws Exception {
        Runs runs = runs();
        replace(runs.candidate().resolve("event-inspection.json"),
            "[\"ANOMALOUS\"]", "[\"ANOMALOUS\",\"REVIEW\"]");
        assertThat(Files.readString(compare(runs, "status").comparisonJson()))
            .contains("\"changes\":[\"status-changed\"]");
    }

    @Test
    void rejectsDifferentDatasetIdentity() throws Exception {
        Runs runs = runs();
        replace(runs.candidate().resolve("kit-evaluation-result.json"),
            "\"corpusId\": \"corpus.mini\"", "\"corpusId\": \"corpus.other\"");
        replace(runs.candidate().resolve("event-inspection.json"),
            "\"corpusId\": \"corpus.mini\"", "\"corpusId\": \"corpus.other\"");
        assertThatThrownBy(() -> compare(runs, "incompatible"))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .hasMessageContaining("corpusId differs");
    }

    @Test
    void rejectsLabeledComparedWithUnlabeled() throws Exception {
        Runs runs = runs();
        Path result = runs.candidate().resolve("kit-evaluation-result.json");
        String text = Files.readString(result)
            .replaceAll("\\s*\"annotationsSha256\"[^\\n]+\\n", "\n");
        Files.writeString(result, text);
        replaceDetectionFamily(result, "not_applicable");
        assertThatThrownBy(() -> compare(runs, "label-mismatch"))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .hasMessageContaining("labeled and unlabeled");
    }

    @Test
    void rejectsLabeledComparisonMissingAnnotationsShaOnBothSides() throws Exception {
        Runs runs = runs();
        for (Path run : new Path[]{runs.baseline(), runs.candidate()}) {
            Path result = run.resolve("kit-evaluation-result.json");
            String text = Files.readString(result)
                .replaceAll("\\s*\"annotationsSha256\"[^\\n]+\\n", "\n");
            Files.writeString(result, text);
        }
        assertThatThrownBy(() -> compare(runs, "missing-annotations-sha-both-sides"))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .hasMessageContaining("annotationsSha256 is required");
    }

    @Test
    void rejectsInspectionMissingIdentityField() throws Exception {
        Runs runs = runs();
        Path inspection = runs.candidate().resolve("event-inspection.json");
        String text = Files.readString(inspection)
            .replaceAll("\\s*\"corpusId\"[^\\n]+\\n", "\n");
        assertThat(text).doesNotContain("\"corpusId\"");
        Files.writeString(inspection, text);
        assertThatThrownBy(() -> compare(runs, "missing-inspection-identity"))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .hasMessageContaining("event-inspection dataset identity differs from result");
    }

    @Test
    void differingThresholdRemainsComparableAndChangesComparisonId() throws Exception {
        Runs runsA = runs();
        replace(runsA.candidate().resolve("event-inspection.json"),
            "\"anomalyThreshold\": 0.5", "\"anomalyThreshold\": 0.6");
        EvaluationComparisonResult first = compare(runsA, "threshold-a");

        Runs runsB = runs();
        replace(runsB.baseline().resolve("event-inspection.json"),
            "\"anomalyThreshold\": 0.5", "\"anomalyThreshold\": 0.05");
        replace(runsB.candidate().resolve("event-inspection.json"),
            "\"anomalyThreshold\": 0.5", "\"anomalyThreshold\": 0.95");
        EvaluationComparisonResult second = compare(runsB, "threshold-b");

        String firstId = extractComparisonId(Files.readString(first.comparisonJson()));
        String secondId = extractComparisonId(Files.readString(second.comparisonJson()));
        assertThat(first.compatible()).isTrue();
        assertThat(first.thresholdEqual()).isFalse();
        assertThat(firstId).isNotEqualTo(secondId);
        assertThat(Files.readString(first.comparisonJson())).contains("\"thresholdEqual\":false");
        assertThat(Files.readString(first.comparisonHtml())).contains("legacy compatibility surrogate");
    }

    private static String extractComparisonId(String json) {
        int start = json.indexOf("\"comparisonId\":\"") + "\"comparisonId\":\"".length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    @Test
    void outputIsDeterministic() throws Exception {
        Runs runs = runs();
        EvaluationComparisonResult first = compare(runs, "deterministic-a");
        EvaluationComparisonResult second = new EvaluationComparisonEngine().compare(
            runs.baseline(), runs.candidate(), tempDir.resolve("deterministic-b"));
        assertThat(Files.readAllBytes(first.comparisonJson()))
            .isEqualTo(Files.readAllBytes(second.comparisonJson()));
        assertThat(Files.readAllBytes(first.comparisonHtml()))
            .isEqualTo(Files.readAllBytes(second.comparisonHtml()));
    }

    @Test
    void rejectsAbsoluteArtifactReference() throws Exception {
        Runs runs = runs();
        replace(runs.candidate().resolve("kit-evaluation-result.json"),
            "\"event-inspection.json\"", "\"/tmp/event-inspection.json\"");
        assertThatThrownBy(() -> compare(runs, "absolute-ref"))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .hasMessageContaining("run-relative");
    }

    private EvaluationComparisonResult compareFresh(String outputName) throws Exception {
        return compare(runs(), outputName);
    }

    private EvaluationComparisonResult compare(Runs runs, String outputName) {
        return new EvaluationComparisonEngine().compare(
            runs.baseline(), runs.candidate(), tempDir.resolve(outputName));
    }

    private Runs runs() throws IOException {
        Path baseline = tempDir.resolve("baseline-" + System.nanoTime());
        Path candidate = tempDir.resolve("candidate-" + System.nanoTime());
        copy(resource("baseline-identical"), baseline);
        copy(resource("candidate-identical"), candidate);
        return new Runs(baseline, candidate);
    }

    private static Path resource(String name) {
        return Path.of("src/test/resources/evaluation-kit-comparison").resolve(name);
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

    private static void replace(Path file, String before, String after) throws IOException {
        String text = Files.readString(file);
        assertThat(text).contains(before);
        Files.writeString(file, text.replace(before, after));
    }

    private static void replaceDetectionFamily(Path file, String availability) throws IOException {
        String text = Files.readString(file);
        int field = text.indexOf("\"detectionLabeled\"");
        int start = text.indexOf('{', field);
        int depth = 0;
        int end = -1;
        for (int i = start; i < text.length(); i++) {
            if (text.charAt(i) == '{') depth++;
            else if (text.charAt(i) == '}' && --depth == 0) {
                end = i + 1;
                break;
            }
        }
        assertThat(field).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String replacement = "{ \"availability\": \"" + availability + "\" }";
        Files.writeString(file, text.substring(0, start) + replacement + text.substring(end));
    }

    private record Runs(Path baseline, Path candidate) {
    }
}
