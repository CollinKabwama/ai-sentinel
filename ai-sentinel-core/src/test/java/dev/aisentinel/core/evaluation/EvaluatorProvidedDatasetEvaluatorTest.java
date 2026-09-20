package dev.aisentinel.core.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluatorProvidedDatasetEvaluatorTest {

    private static final DetectionClassificationConfiguration THRESHOLD =
        new DetectionClassificationConfiguration(0.5);

    @TempDir
    Path tempDir;

    @Test
    void labeledDatasetEvaluatesEndToEndWithoutGeneratorFields() throws Exception {
        Path out = tempDir.resolve("labeled-out");
        EvaluatorProvidedDatasetEvaluationResult result = new EvaluatorProvidedDatasetEvaluator().evaluate(
            fixture("minimal-labeled"),
            dev.aisentinel.core.replay.ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            out
        );

        assertThat(result.provenance().datasetId()).isEqualTo("byo.minimal.labeled.001");
        assertThat(result.provenance().labeled()).isTrue();
        assertThat(result.phaseCounts().totalEvents()).isEqualTo(4);
        assertThat(result.phaseCounts().warmupEvents()).isEqualTo(2);
        assertThat(result.phaseCounts().labeledEvaluationEvents()).isEqualTo(2);
        assertThat(result.eventInspections()).hasSize(4);
        assertThat(result.limitations()).isNotEmpty();

        String kit = Files.readString(out.resolve("kit-evaluation-result.json"));
        assertThat(kit).contains("\"datasetSource\":\"evaluator-provided\"");
        assertThat(kit).contains("\"datasetId\":\"byo.minimal.labeled.001\"");
        assertThat(kit).doesNotContain("\"seed\"");
        assertThat(kit).doesNotContain("generatorBuildId");
        assertThat(kit).doesNotContain("generatorContractVersion");
        assertThat(kit).doesNotContain("\"corpusId\"");

        String inspection = Files.readString(out.resolve("event-inspection.json"));
        assertThat(inspection).contains("\"datasetSource\":\"evaluator-provided\"");
        assertThat(inspection).contains("\"datasetId\":\"byo.minimal.labeled.001\"");
        assertThat(inspection).doesNotContain("\"corpusId\"");

        assertThat(Files.isRegularFile(out.resolve("evaluation-report.html"))).isTrue();
        assertThat(Files.isRegularFile(out.resolve("evaluation.json"))).isTrue();
    }

    @Test
    void unlabeledDatasetEvaluatesWithUnavailableDetectionMetrics() throws Exception {
        Path out = tempDir.resolve("unlabeled-out");
        EvaluatorProvidedDatasetEvaluationResult result = new EvaluatorProvidedDatasetEvaluator().evaluate(
            fixture("minimal-unlabeled"),
            dev.aisentinel.core.replay.ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            out
        );

        assertThat(result.provenance().labeled()).isFalse();
        assertThat(result.phaseCounts().unknownOrUnlabeledEvents()).isEqualTo(4);
        assertThat(result.phaseCounts().labeledEvaluationEvents()).isZero();
        assertThat(result.detectionRun().alignment().evaluableObservationCount()).isZero();

        String kit = Files.readString(out.resolve("kit-evaluation-result.json"));
        assertThat(kit).contains("\"datasetSource\":\"evaluator-provided\"");
        assertThat(kit).contains("\"availability\":\"unavailable\"");
        assertThat(kit).doesNotContain("\"seed\"");
        assertThat(kit).doesNotContain("generatorBuildId");
    }

    @Test
    void eventOrderMatchesAppendOrder() throws Exception {
        Path out = tempDir.resolve("order-out");
        EvaluatorProvidedDatasetEvaluationResult result = new EvaluatorProvidedDatasetEvaluator().evaluate(
            fixture("minimal-labeled"),
            dev.aisentinel.core.replay.ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            out
        );
        assertThat(result.eventInspections())
            .extracting(GeneratedCorpusEventInspection::eventId)
            .containsExactly("evt-byo-0001", "evt-byo-0002", "evt-byo-0003", "evt-byo-0004");
        assertThat(result.eventInspections())
            .extracting(GeneratedCorpusEventInspection::sequenceNumber)
            .containsExactly(1, 2, 3, 4);
    }

    @Test
    void groundTruthIsolationRejectsLabelsInEvents() throws Exception {
        Path copy = copyFixture("minimal-labeled");
        Path events = copy.resolve("events.jsonl");
        String polluted = Files.readString(events).replaceFirst(
            "\\{",
            "{\"expectedClass\":\"anomalous\","
        );
        Files.writeString(events, polluted);
        rewriteEventsChecksum(copy);

        assertThatThrownBy(() -> new EvaluatorProvidedDatasetEvaluator().evaluate(
            copy,
            dev.aisentinel.core.replay.ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("gt-isolation-out")
        ))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .extracting(ex -> ((GeneratedCorpusEvaluationException) ex).failureKind())
            .isIn(
                GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE,
                GeneratedCorpusEvaluationFailureKind.UNSUPPORTED_SCHEMA,
                GeneratedCorpusEvaluationFailureKind.EVALUATION_FAILURE
            );
    }

    @Test
    void checksumTamperingIsRejected() throws Exception {
        Path copy = copyFixture("minimal-labeled");
        Path events = copy.resolve("events.jsonl");
        Files.writeString(events, Files.readString(events) + " ", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new EvaluatorProvidedDatasetEvaluator().evaluate(
            copy,
            dev.aisentinel.core.replay.ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("tamper-out")
        ))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .extracting(ex -> ((GeneratedCorpusEvaluationException) ex).failureKind())
            .isEqualTo(GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE);
    }

    @Test
    void fabricatedGeneratorFieldsInManifestAreRejected() throws Exception {
        Path copy = copyFixture("minimal-unlabeled");
        Path manifest = copy.resolve("dataset-manifest.json");
        String text = Files.readString(manifest);
        Files.writeString(
            manifest,
            text.replaceFirst(
                "\"notes\":",
                "\"seed\":\"42\",\"generatorBuildId\":\"fabricated\",\"notes\":"
            )
        );

        assertThatThrownBy(() -> new EvaluatorProvidedDatasetEvaluator().evaluate(
            copy,
            dev.aisentinel.core.replay.ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("fabricated-out")
        ))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .hasMessageContaining("must not include generator/corpus provenance");
    }

    @Test
    void annotationsWithCorpusIdAreRejected() throws Exception {
        Path copy = copyFixture("minimal-labeled");
        Path annotations = copy.resolve("annotations.json");
        String text = Files.readString(annotations);
        Files.writeString(annotations, text.replace("\"datasetId\"", "\"corpusId\""));
        rewriteAnnotationsChecksum(copy);

        assertThatThrownBy(() -> new EvaluatorProvidedDatasetEvaluator().evaluate(
            copy,
            dev.aisentinel.core.replay.ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("corpusId-out")
        ))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .extracting(ex -> ((GeneratedCorpusEvaluationException) ex).failureKind())
            .isEqualTo(GeneratedCorpusEvaluationFailureKind.GROUND_TRUTH_FAILURE);
    }

    @Test
    void missingManifestFails() throws Exception {
        Path copy = copyFixture("minimal-unlabeled");
        Files.delete(copy.resolve("dataset-manifest.json"));
        assertThatThrownBy(() -> new EvaluatorProvidedDatasetEvaluator().evaluate(
            copy,
            dev.aisentinel.core.replay.ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("missing-manifest-out")
        ))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .extracting(ex -> ((GeneratedCorpusEvaluationException) ex).failureKind())
            .isEqualTo(GeneratedCorpusEvaluationFailureKind.MISSING_ARTIFACT);
    }

    private static Path fixture(String name) {
        Path resource = Path.of("src/test/resources/evaluation-kit-byo").resolve(name);
        if (Files.isDirectory(resource)) {
            return resource.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Missing BYO fixture: " + resource.toAbsolutePath());
    }

    private Path copyFixture(String name) throws Exception {
        Path source = fixture(name);
        Path destination = tempDir.resolve("copy-" + name);
        Files.walk(source).forEach(path -> {
            try {
                Path relative = source.relativize(path);
                Path target = destination.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        return destination;
    }

    private static void rewriteEventsChecksum(Path datasetDir) throws Exception {
        Path events = datasetDir.resolve("events.jsonl");
        String sha = sha256Hex(Files.readAllBytes(events));
        Path manifest = datasetDir.resolve("dataset-manifest.json");
        String text = Files.readString(manifest);
        String updated = text.replaceFirst(
            "(\"events\"\\s*:\\s*\\{[^}]*\"sha256\"\\s*:\\s*\")[0-9a-f]{64}",
            "$1" + sha
        );
        if (updated.equals(text)) {
            throw new IllegalStateException("Unable to rewrite events checksum");
        }
        Files.writeString(manifest, updated);
    }

    private static void rewriteAnnotationsChecksum(Path datasetDir) throws Exception {
        Path annotations = datasetDir.resolve("annotations.json");
        String sha = sha256Hex(Files.readAllBytes(annotations));
        Path manifest = datasetDir.resolve("dataset-manifest.json");
        String text = Files.readString(manifest);
        String updated = text.replaceFirst(
            "(\"annotations\"\\s*:\\s*\\{[^}]*\"sha256\"\\s*:\\s*\")[0-9a-f]{64}",
            "$1" + sha
        );
        if (updated.equals(text)) {
            throw new IllegalStateException("Unable to rewrite annotations checksum");
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
}
