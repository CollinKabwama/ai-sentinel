package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetExpectedClass;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeneratedCorpusDetectionEvaluatorTest {

    private static final DetectionClassificationConfiguration THRESHOLD =
        new DetectionClassificationConfiguration(0.5);

    @TempDir
    Path tempDir;

    @Test
    void allCheckedInGeneratedCorporaEvaluateEndToEnd() throws Exception {
        Path kitRoot = repoRoot().resolve("evaluation/kit-reference");
        GeneratedCorpusDetectionEvaluator evaluator = new GeneratedCorpusDetectionEvaluator();
        int totalEvents = 0;

        try (var stream = Files.list(kitRoot.resolve("corpora"))) {
            List<Path> corpora = stream.filter(Files::isDirectory).sorted().toList();
            assertThat(corpora).hasSize(11);
            for (Path corpusDir : corpora) {
                Path out = tempDir.resolve(corpusDir.getFileName().toString() + "-evidence");
                GeneratedCorpusEvaluationResult result = evaluator.evaluate(
                    corpusDir,
                    ReplayConfiguration.referenceDefaults(),
                    THRESHOLD,
                    out
                );
                totalEvents += result.phaseCounts().totalEvents();
                assertThat(result.provenance().corpusId()).isNotBlank();
                assertThat(result.provenance().scenarioId()).isNotBlank();
                assertThat(result.provenance().generatorBuildId())
                    .isEqualTo("aisentinel-kit-reference-corpus@1");
                assertThat(result.phaseCounts().totalEvents())
                    .isEqualTo(result.provenance().eventCount());
                assertThat(result.phaseCounts().warmupEvents()
                    + result.phaseCounts().unknownOrUnlabeledEvents()
                    + result.phaseCounts().labeledEvaluationEvents())
                    .isLessThanOrEqualTo(result.phaseCounts().totalEvents());
                assertThat(result.detectionRun().alignment().referenceEventCount())
                    .isEqualTo(result.phaseCounts().totalEvents());
                assertThat(result.detectionRun().alignment().evaluableObservationCount())
                    .isEqualTo(result.phaseCounts().labeledEvaluationEvents());
                assertThat(result.limitations()).isNotEmpty();
                assertThat(result.detectionRun().writtenEvidence().jsonBytes()).isPositive();
                assertThat(Files.list(out).findAny()).isPresent();
            }
        }
        assertThat(totalEvents).isEqualTo(107);
    }

    @Test
    void warmupEventsAreExcludedFromBinaryDetectionObservations() throws Exception {
        Path corpusDir = repoRoot().resolve("evaluation/kit-reference/corpora/kit.abrupt-burst");
        GeneratedCorpusEvaluationResult result = new GeneratedCorpusDetectionEvaluator().evaluate(
            corpusDir,
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("warmup-exclusion")
        );
        assertThat(result.phaseCounts().warmupEvents()).isEqualTo(4);
        assertThat(result.phaseCounts().labeledEvaluationEvents()).isEqualTo(6);
        assertThat(result.detectionRun().alignment().evaluableObservationCount()).isEqualTo(6);
        assertThat(result.detectionRun().alignment().observations())
            .noneMatch(observation -> observation.eventId().endsWith("-0001")
                || observation.eventId().endsWith("-0002")
                || observation.eventId().endsWith("-0003")
                || observation.eventId().endsWith("-0004"));
    }

    @Test
    void unknownLabelsAreExcludedFromBinaryMetricsAndCountedSeparately() throws Exception {
        Path corpusDir = repoRoot()
            .resolve("evaluation/kit-reference/corpora/kit.invalid-score-degradation");
        GeneratedCorpusEvaluationResult result = new GeneratedCorpusDetectionEvaluator().evaluate(
            corpusDir,
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("unknown-labels")
        );
        assertThat(result.phaseCounts().unknownOrUnlabeledEvents()).isEqualTo(4);
        assertThat(result.phaseCounts().labeledBenignEvents()).isEqualTo(2);
        assertThat(result.phaseCounts().labeledAnomalousEvents()).isEqualTo(0);
        assertThat(result.detectionRun().alignment().evaluableObservationCount()).isEqualTo(2);
        assertThat(result.detectionRun().metrics().confusionMatrix().truePositives()
            + result.detectionRun().metrics().confusionMatrix().falseNegatives()).isZero();
    }

    @Test
    void runtimeStatusesComeFromReplayNotGroundTruth() throws Exception {
        Path corpusDir = repoRoot().resolve("evaluation/kit-reference/corpora/kit.established-normal");
        GeneratedCorpusEvaluationResult result = new GeneratedCorpusDetectionEvaluator().evaluate(
            corpusDir,
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("runtime-status")
        );
        // Source events must remain status-empty; replay may attach lifecycle statuses.
        ReplayDataset source = new ReplayDatasetLoader().load(corpusDir);
        assertThat(source.events()).allSatisfy(event ->
            assertThat(event.historicalOutput().evaluationStatuses()).isEmpty());
        assertThat(result.detectionRun().alignment().observations()).allSatisfy(observation ->
            assertThat(observation.truth().expectedClass())
                .isIn(ReferenceDatasetExpectedClass.NORMAL, ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS));
    }

    @Test
    void eventChecksumTamperingIsRejected() throws Exception {
        Path copy = copyCorpus("kit.established-normal");
        Path events = copy.resolve("events.jsonl");
        Files.writeString(events, Files.readString(events, StandardCharsets.UTF_8) + " ", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new GeneratedCorpusDetectionEvaluator().evaluate(
            copy,
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("tamper-events-out")
        ))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .extracting(ex -> ((GeneratedCorpusEvaluationException) ex).failureKind())
            .isEqualTo(GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE);
    }

    @Test
    void annotationChecksumTamperingIsRejected() throws Exception {
        Path copy = copyCorpus("kit.established-normal");
        Path annotations = copy.resolve("annotations.json");
        Files.writeString(
            annotations,
            Files.readString(annotations, StandardCharsets.UTF_8).replace("benign", "anomalous"),
            StandardCharsets.UTF_8
        );
        assertThatThrownBy(() -> new GeneratedCorpusDetectionEvaluator().evaluate(
            copy,
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("tamper-ann-out")
        ))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .extracting(ex -> ((GeneratedCorpusEvaluationException) ex).failureKind())
            .isEqualTo(GeneratedCorpusEvaluationFailureKind.INTEGRITY_FAILURE);
    }

    @Test
    void duplicateAnnotationEventIdFails() {
        GeneratedCorpusGroundTruth gt = new GeneratedCorpusGroundTruth(
            "1",
            "ann.x",
            "corpus.x",
            "scenario.x",
            List.of(
                new GeneratedCorpusGroundTruth.EventAnnotation("e1", "scenario.x", "benign", "warmup"),
                new GeneratedCorpusGroundTruth.EventAnnotation("e1", "scenario.x", "benign", "evaluation-normal")
            ),
            ""
        );
        assertThatThrownBy(() -> GeneratedCorpusSupport.validateGroundTruthAgainstEvents(gt, java.util.Set.of("e1")))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .hasMessageContaining("Duplicate annotation eventId");
    }

    @Test
    void annotationForNonexistentEventFails() {
        GeneratedCorpusGroundTruth gt = new GeneratedCorpusGroundTruth(
            "1",
            "ann.x",
            "corpus.x",
            "scenario.x",
            List.of(new GeneratedCorpusGroundTruth.EventAnnotation("missing", "scenario.x", "benign", "warmup")),
            ""
        );
        assertThatThrownBy(() -> GeneratedCorpusSupport.validateGroundTruthAgainstEvents(gt, java.util.Set.of("e1")))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .hasMessageContaining("nonexistent eventId");
    }

    @Test
    void eventMissingAnnotationFails() {
        GeneratedCorpusGroundTruth gt = new GeneratedCorpusGroundTruth(
            "1",
            "ann.x",
            "corpus.x",
            "scenario.x",
            List.of(new GeneratedCorpusGroundTruth.EventAnnotation("e1", "scenario.x", "benign", "warmup")),
            ""
        );
        assertThatThrownBy(() ->
            GeneratedCorpusSupport.validateGroundTruthAgainstEvents(gt, java.util.Set.of("e1", "e2")))
            .isInstanceOf(GeneratedCorpusEvaluationException.class)
            .hasMessageContaining("missing ground-truth annotation");
    }

    @Test
    void adapterMapsBenignAndAnomalousWithoutMaliciousness() {
        GeneratedCorpusProvenance provenance = new GeneratedCorpusProvenance(
            "corpus.example",
            "kit.example.v1",
            "1.0.0",
            "a".repeat(64),
            "seed-1",
            "1",
            "build@1",
            "1",
            "1",
            "feature-level",
            "b".repeat(64),
            "c".repeat(64),
            3,
            1,
            2
        );
        GeneratedCorpusGroundTruth gt = new GeneratedCorpusGroundTruth(
            "1",
            "ann.example",
            "corpus.example",
            "kit.example.v1",
            List.of(
                new GeneratedCorpusGroundTruth.EventAnnotation("e1", "kit.example.v1", "benign", "warmup"),
                new GeneratedCorpusGroundTruth.EventAnnotation("e2", "kit.example.v1", "benign", "evaluation-normal"),
                new GeneratedCorpusGroundTruth.EventAnnotation("e3", "kit.example.v1", "anomalous", "burst")
            ),
            ""
        );
        ReferenceDatasetAnnotations adapted =
            GeneratedCorpusAnnotationAdapter.toReferenceAnnotations(provenance, gt);
        assertThat(adapted.datasetId()).isEqualTo("corpus.example");
        assertThat(adapted.scenarios()).hasSize(2);
        assertThat(adapted.scenarios()).allSatisfy(scenario ->
            assertThat(scenario.maliciousnessAsserted()).isFalse());
    }

    @Test
    void objectOverloadEnforcesSameConsistencyChecksAsFilePathEntryPoint() throws Exception {
        // The package-private ReplayDataset/ReferenceDatasetAnnotations overload must not accept a
        // weaker guarantee than the public file-path entry point: schemaVersion and scenarioCount
        // must agree with the dataset's own annotation metadata, not just datasetId.
        Path corpusDir = repoRoot().resolve("evaluation/kit-reference/corpora/kit.established-normal");
        ReplayDataset dataset = new ReplayDatasetLoader().load(corpusDir);
        ReferenceDatasetAnnotations mismatched = new ReferenceDatasetAnnotations(
            ReferenceDatasetAnnotations.SCHEMA_VERSION,
            dataset.manifest().datasetId(),
            "mismatched scenarioCount",
            List.of()
        );
        ReplayDataset annotatedDataset = dataset.withAnnotations(
            new ReplayDataset.AnnotationMetadata(ReferenceDatasetAnnotations.SCHEMA_VERSION, dataset.manifest().datasetId(), 5));

        assertThatThrownBy(() -> new DetectionEvaluationRunner().evaluate(
            annotatedDataset,
            mismatched,
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD,
            tempDir.resolve("consistency-check-out")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scenarioCount");
    }

    @Test
    void historicalReferenceStillLoadsAndReplaysSeparately() throws Exception {
        Path reference = repoRoot().resolve("evaluation/reference");
        ReplayDataset dataset = new ReplayDatasetLoader().load(reference, reference.resolve("annotations.json"));
        assertThat(dataset.events()).hasSize(136);
        assertThat(dataset.manifest().eventsSha256())
            .isEqualTo("1c4178816d33dd460dc233167d5b1e770ba25a6694a772407f2d01dc21e4eded");
    }

    private Path copyCorpus(String name) throws Exception {
        Path source = repoRoot().resolve("evaluation/kit-reference/corpora").resolve(name);
        Path dest = tempDir.resolve("copy-" + name);
        Files.createDirectories(dest);
        for (String file : List.of(
            "events.jsonl", "annotations.json", "corpus-manifest.json", "manifest.json")) {
            Files.copy(source.resolve(file), dest.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.exists(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }
}
