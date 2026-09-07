package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotationsLoader;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetExpectedClass;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioAnnotation;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.replay.HistoricalReferenceOutput;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;
import dev.aisentinel.core.replay.ReplayEngine;
import dev.aisentinel.core.replay.ReplayResult;
import dev.aisentinel.core.replay.ReplayResultStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceEvaluationAlignerTest {

    @TempDir
    Path tempDir;

    @Test
    void alignsReferenceCorpusDeterministicallyWithStableOrdering() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        List<ReplayResult> replayResults = runReplay(dataset);

        ReferenceEvaluationAlignment first = new ReferenceEvaluationAligner().align(dataset, annotations, replayResults);
        ReferenceEvaluationAlignment second = new ReferenceEvaluationAligner().align(dataset, annotations, replayResults);

        assertThat(first).isEqualTo(second);
        assertThat(first.referenceEventCount()).isEqualTo(136);
        assertThat(first.scenarioCount()).isEqualTo(11);
        assertThat(first.evaluableObservationCount()).isEqualTo(84);
        assertThat(first.observations()).hasSize(84);
        assertThat(first.observations()).extracting(EvaluationObservation::eventId)
            .containsExactlyElementsOf(expectedEvaluableEventIds(annotations));
        assertThat(first.observations().getFirst().sequenceNumber()).isEqualTo(1);
        assertThat(first.observations().getLast().sequenceNumber()).isEqualTo(136);
    }

    @Test
    void preservesNormalAnomalousAndLegitimateAnomalousTruth() throws Exception {
        ReferenceEvaluationAlignment alignment = alignTrackedCorpus();

        assertThat(alignment.observations()).extracting(obs -> obs.truth().expectedClass())
            .contains(
                ReferenceDatasetExpectedClass.NORMAL,
                ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
                ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS
            );
        assertThat(alignment.observations()).filteredOn(obs -> obs.truth().expectedClass() == ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS)
            .allSatisfy(obs -> {
                assertThat(obs.truth().anomalousExpected()).isTrue();
                assertThat(obs.truth().maliciousnessAsserted()).isFalse();
            });
        assertThat(alignment.observations()).filteredOn(obs -> obs.truth().expectedClass() == ReferenceDatasetExpectedClass.NORMAL)
            .allSatisfy(obs -> {
                assertThat(obs.truth().anomalousExpected()).isFalse();
                assertThat(obs.truth().maliciousnessAsserted()).isFalse();
            });
        assertThat(alignment.observations()).filteredOn(obs -> obs.truth().expectedClass() == ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS)
            .allSatisfy(obs -> {
                assertThat(obs.truth().anomalousExpected()).isTrue();
                assertThat(obs.truth().maliciousnessAsserted()).isFalse();
            });
    }

    @Test
    void rejectsContradictoryTruthMetadata() {
        assertThatThrownBy(() -> new EvaluationTruth(ReferenceDatasetExpectedClass.NORMAL, true, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("contradicts");
        assertThatThrownBy(() -> new EvaluationTruth(ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS, false, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("contradicts");
    }

    @Test
    void rejectsContradictoryScenarioTruthDuringAlignment() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        ReferenceDatasetScenarioAnnotation normal = annotations.scenarios().getFirst();
        ReferenceDatasetScenarioAnnotation contradictory = new ReferenceDatasetScenarioAnnotation(
            normal.id(),
            normal.category(),
            ReferenceDatasetExpectedClass.NORMAL,
            true,
            normal.maliciousnessAsserted(),
            normal.eventIds(),
            normal.baselineEventIds(),
            normal.evaluationEventIds(),
            normal.identityKeys(),
            normal.exercisedFeatures(),
            normal.notes()
        );
        List<ReferenceDatasetScenarioAnnotation> scenarios = new ArrayList<>(annotations.scenarios());
        scenarios.set(0, contradictory);
        ReferenceDatasetAnnotations contradictoryAnnotations = new ReferenceDatasetAnnotations(
            annotations.schemaVersion(),
            annotations.datasetId(),
            annotations.description(),
            scenarios
        );

        assertThatThrownBy(() -> new ReferenceEvaluationAligner().align(dataset, contradictoryAnnotations, runReplay(dataset)))
            .isInstanceOf(EvaluationAlignmentException.class)
            .hasMessageContaining("truth metadata");
    }

    @Test
    void rejectsMissingReplayEvent() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        List<ReplayResult> replayResults = new ArrayList<>(runReplay(dataset));
        replayResults.removeIf(result -> result.eventId().equals("evt-ref-0023"));

        assertThatThrownBy(() -> new ReferenceEvaluationAligner().align(dataset, annotations, replayResults))
            .isInstanceOf(EvaluationAlignmentException.class)
            .hasMessageContaining("missing replay event");
    }

    @Test
    void rejectsUnexpectedReplayEvent() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        List<ReplayResult> replayResults = new ArrayList<>(runReplay(dataset));
        ReplayResult first = replayResults.getFirst();
        replayResults.add(new ReplayResult(
            first.replaySchemaVersion(),
            first.replayRunId(),
            ReplayResultStatus.REPLAYED,
            999,
            "evt-ref-unexpected",
            first.correlationId(),
            first.identityKey(),
            first.identityType(),
            first.observedAt(),
            first.endpointKey(),
            first.featureSchemaVersion(),
            first.scorerId(),
            first.scorerVersion(),
            first.anomalyScore(),
            first.policyScore(),
            first.action(),
            first.evaluationStatuses(),
            List.of(),
            first.policyId(),
            first.policyVersion(),
            first.evaluationMode()
        ));

        assertThatThrownBy(() -> new ReferenceEvaluationAligner().align(dataset, annotations, replayResults))
            .isInstanceOf(EvaluationAlignmentException.class)
            .hasMessageContaining("unknown source event");
    }

    @Test
    void rejectsDuplicateReplayEvent() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        List<ReplayResult> replayResults = new ArrayList<>(runReplay(dataset));
        replayResults.add(replayResults.getFirst());

        assertThatThrownBy(() -> new ReferenceEvaluationAligner().align(dataset, annotations, replayResults))
            .isInstanceOf(EvaluationAlignmentException.class)
            .hasMessageContaining("duplicate replay event id");
    }

    @Test
    void rejectsReplayResultThatDoesNotMatchSourceEvent() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        ReplayResult base = runReplay(dataset).stream()
            .filter(result -> result.eventId().equals("evt-ref-0023"))
            .findFirst()
            .orElseThrow();
        List<ReplayResult> mutated = mutateResult(runReplay(dataset), "evt-ref-0023", new ReplayResult(
            base.replaySchemaVersion(),
            base.replayRunId(),
            base.replayStatus(),
            base.sequenceNumber() + 1,
            base.eventId(),
            base.correlationId(),
            base.identityKey(),
            base.identityType(),
            base.observedAt(),
            base.endpointKey(),
            base.featureSchemaVersion(),
            base.scorerId(),
            base.scorerVersion(),
            base.anomalyScore(),
            base.policyScore(),
            base.action(),
            base.evaluationStatuses(),
            base.riskFactors(),
            base.policyId(),
            base.policyVersion(),
            base.evaluationMode()
        ));

        assertThatThrownBy(() -> new ReferenceEvaluationAligner().align(dataset, annotations, mutated))
            .isInstanceOf(EvaluationAlignmentException.class)
            .hasMessageContaining("does not match source event");
    }

    @Test
    void rejectsConflictingScenarioMembership() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        List<ReplayResult> replayResults = runReplay(dataset);
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        ReferenceDatasetScenarioAnnotation normal = annotations.scenarios().getFirst();
        ReferenceDatasetScenarioAnnotation conflicting = new ReferenceDatasetScenarioAnnotation(
            "conflicting-scenario",
            ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST,
            ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS,
            true,
            false,
            List.of("evt-ref-0001"),
            List.of(),
            List.of("evt-ref-0001"),
            normal.identityKeys(),
            List.of("requestsPerWindow"),
            "conflict"
        );
        ReferenceDatasetAnnotations conflictingAnnotations = new ReferenceDatasetAnnotations(
            annotations.schemaVersion(),
            annotations.datasetId(),
            annotations.description(),
            append(annotations.scenarios(), conflicting)
        );

        assertThatThrownBy(() -> new ReferenceEvaluationAligner().align(dataset, conflictingAnnotations, replayResults))
            .isInstanceOf(EvaluationAlignmentException.class)
            .hasMessageContaining("conflicting annotations");
    }

    @Test
    void annotationsLoaderRejectsUnsupportedClassification() throws Exception {
        Path annotationsFile = tempDir.resolve("annotations.json");
        String badAnnotations = Files.readString(repoRoot().resolve("evaluation/reference/annotations.json"), StandardCharsets.UTF_8)
            .replaceFirst("\"expectedClass\":\"NORMAL\"", "\"expectedClass\":\"UNSUPPORTED_CLASS\"");
        Files.writeString(annotationsFile, badAnnotations, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new ReferenceDatasetAnnotationsLoader().load(annotationsFile))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Failed to parse");
    }

    @Test
    void historicalOutputFieldsDoNotBecomeTruthOrPrediction() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        List<ReplayResult> replayResults = runReplay(dataset);
        ReplayDataset mutatedDataset = new ReplayDataset(
            dataset.manifest(),
            dataset.eventsSha256(),
            replaceFirstHistoricalOutput(dataset.events(), historical -> new HistoricalReferenceOutput(
                historical.scorerId(),
                historical.scorerVersion(),
                0.99,
                0.99,
                EnforcementAction.BLOCK,
                List.of(EvaluationStatus.COMPLETE),
                historical.riskFactors(),
                historical.policyId(),
                historical.policyVersion(),
                historical.evaluationMode()
            )),
            dataset.annotations()
        );

        EvaluationObservation observation = new ReferenceEvaluationAligner()
            .align(mutatedDataset, annotations, replayResults)
            .observations()
            .getFirst();

        assertThat(observation.truth().expectedClass()).isEqualTo(ReferenceDatasetExpectedClass.NORMAL);
        assertThat(observation.prediction().action()).isEqualTo(replayResults.getFirst().action());
        assertThat(observation.prediction().source()).isEqualTo(EvaluationPredictionSource.REPLAY_SCORE);
        assertThat(observation.prediction().anomalyScore()).isEqualTo(replayResults.getFirst().anomalyScore());
    }

    @Test
    void scenarioMetadataDoesNotExposeFeatureSnapshotsInEvaluationContract() {
        assertThat(recordComponentNames(EvaluationObservation.class))
            .doesNotContain("features");
        assertThat(recordComponentNames(EvaluationPrediction.class))
            .doesNotContain("expectedClass", "scenarioId", "anomalousPredicted");
    }

    @Test
    void monitorActionIsNotABinaryDetectorPrediction() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        ReplayResult base = runReplay(dataset).stream()
            .filter(result -> result.eventId().equals("evt-ref-0001"))
            .findFirst()
            .orElseThrow();
        List<ReplayResult> monitorResult = mutateResult(runReplay(dataset), "evt-ref-0001", new ReplayResult(
            base.replaySchemaVersion(),
            base.replayRunId(),
            base.replayStatus(),
            base.sequenceNumber(),
            base.eventId(),
            base.correlationId(),
            base.identityKey(),
            base.identityType(),
            base.observedAt(),
            base.endpointKey(),
            base.featureSchemaVersion(),
            base.scorerId(),
            base.scorerVersion(),
            0.05,
            0.05,
            EnforcementAction.MONITOR,
            List.of(EvaluationStatus.STATISTICAL_WARMUP),
            List.of(),
            base.policyId(),
            base.policyVersion(),
            base.evaluationMode()
        ));

        EvaluationPrediction prediction = new ReferenceEvaluationAligner()
            .align(dataset, annotations, monitorResult)
            .observations()
            .getFirst()
            .prediction();

        assertThat(prediction.source()).isEqualTo(EvaluationPredictionSource.REPLAY_SCORE);
        assertThat(prediction.anomalyScore()).isEqualTo(0.05);
        assertThat(prediction.action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(prediction.hasValidDetectorScore()).isTrue();
    }

    @Test
    void invalidScoreDoesNotBecomePositiveDetection() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        ReplayResult base = runReplay(dataset).stream()
            .filter(result -> result.eventId().equals("evt-ref-0023"))
            .findFirst()
            .orElseThrow();
        List<ReplayResult> mutated = mutateResult(runReplay(dataset), "evt-ref-0023", new ReplayResult(
            base.replaySchemaVersion(),
            base.replayRunId(),
            base.replayStatus(),
            base.sequenceNumber(),
            base.eventId(),
            base.correlationId(),
            base.identityKey(),
            base.identityType(),
            base.observedAt(),
            base.endpointKey(),
            base.featureSchemaVersion(),
            base.scorerId(),
            base.scorerVersion(),
            null,
            null,
            EnforcementAction.ALLOW,
            List.of(EvaluationStatus.INVALID_SCORE),
            List.of(),
            base.policyId(),
            base.policyVersion(),
            base.evaluationMode()
        ));

        EvaluationObservation observation = new ReferenceEvaluationAligner().align(dataset, annotations, mutated).observations().stream()
            .filter(obs -> obs.eventId().equals("evt-ref-0023"))
            .findFirst()
            .orElseThrow();

        assertThat(observation.prediction().anomalyScore()).isNull();
        assertThat(observation.prediction().hasValidDetectorScore()).isFalse();
        assertThat(observation.prediction().evaluationStatuses()).contains(EvaluationStatus.INVALID_SCORE);
    }

    @Test
    void infrastructureFailureDoesNotBecomeAttackTruth() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        ReferenceDatasetAnnotations annotations = loadTrackedAnnotations();
        ReplayResult base = runReplay(dataset).stream()
            .filter(result -> result.eventId().equals("evt-ref-0023"))
            .findFirst()
            .orElseThrow();
        List<ReplayResult> mutated = mutateResult(runReplay(dataset), "evt-ref-0023", new ReplayResult(
            base.replaySchemaVersion(),
            base.replayRunId(),
            base.replayStatus(),
            base.sequenceNumber(),
            base.eventId(),
            base.correlationId(),
            base.identityKey(),
            base.identityType(),
            base.observedAt(),
            base.endpointKey(),
            base.featureSchemaVersion(),
            base.scorerId(),
            base.scorerVersion(),
            null,
            null,
            EnforcementAction.ALLOW,
            List.of(EvaluationStatus.REMOTE_EVALUATION_FAILURE),
            List.of(),
            base.policyId(),
            base.policyVersion(),
            base.evaluationMode()
        ));

        EvaluationObservation observation = new ReferenceEvaluationAligner().align(dataset, annotations, mutated).observations().stream()
            .filter(obs -> obs.eventId().equals("evt-ref-0023"))
            .findFirst()
            .orElseThrow();

        assertThat(observation.truth().expectedClass()).isEqualTo(ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS);
        assertThat(observation.truth().maliciousnessAsserted()).isFalse();
        assertThat(observation.prediction().anomalyScore()).isNull();
        assertThat(observation.prediction().hasValidDetectorScore()).isFalse();
        assertThat(observation.prediction().evaluationStatuses()).contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE);
    }

    @Test
    void returnedCollectionsAreImmutable() throws Exception {
        ReferenceEvaluationAlignment alignment = alignTrackedCorpus();

        assertThatThrownBy(() -> alignment.observations().add(alignment.observations().getFirst()))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> alignment.observations().getFirst().prediction().evaluationStatuses().add(EvaluationStatus.COMPLETE))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private ReferenceEvaluationAlignment alignTrackedCorpus() throws Exception {
        ReplayDataset dataset = loadTrackedDataset();
        return new ReferenceEvaluationAligner().align(dataset, loadTrackedAnnotations(), runReplay(dataset));
    }

    private ReplayDataset loadTrackedDataset() throws IOException {
        return new ReplayDatasetLoader().load(repoRoot().resolve("evaluation/reference"));
    }

    private ReferenceDatasetAnnotations loadTrackedAnnotations() throws IOException {
        return new ReferenceDatasetAnnotationsLoader().load(repoRoot().resolve("evaluation/reference/annotations.json"));
    }

    private List<ReplayResult> runReplay(ReplayDataset dataset) throws IOException {
        Path outputDir = Files.createTempDirectory(tempDir, "replay-");
        return new ReplayEngine().run(dataset, ReplayConfiguration.referenceDefaults(), outputDir).results();
    }

    private static List<String> expectedEvaluableEventIds(ReferenceDatasetAnnotations annotations) {
        return annotations.scenarios().stream()
            .flatMap(scenario -> ReferenceEvaluationAligner.evaluableEventIds(scenario).stream())
            .toList();
    }

    private static List<ReferenceDatasetScenarioAnnotation> append(List<ReferenceDatasetScenarioAnnotation> scenarios,
                                                                   ReferenceDatasetScenarioAnnotation scenario) {
        List<ReferenceDatasetScenarioAnnotation> copy = new ArrayList<>(scenarios);
        copy.add(scenario);
        return List.copyOf(copy);
    }

    private static List<ReplayDataset.ReplaySourceEvent> replaceFirstHistoricalOutput(
        List<ReplayDataset.ReplaySourceEvent> events,
        Function<HistoricalReferenceOutput, HistoricalReferenceOutput> replacer
    ) {
        List<ReplayDataset.ReplaySourceEvent> copy = new ArrayList<>(events);
        ReplayDataset.ReplaySourceEvent first = copy.getFirst();
        copy.set(0, new ReplayDataset.ReplaySourceEvent(first.replayInput(), replacer.apply(first.historicalOutput())));
        return List.copyOf(copy);
    }

    private static List<String> recordComponentNames(Class<?> type) {
        return List.of(type.getRecordComponents()).stream().map(RecordComponent::getName).toList();
    }

    private static List<ReplayResult> mutateResult(List<ReplayResult> results, String eventId, ReplayResult replacement) {
        return results.stream()
            .map(result -> result.eventId().equals(eventId) ? replacement : result)
            .toList();
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
