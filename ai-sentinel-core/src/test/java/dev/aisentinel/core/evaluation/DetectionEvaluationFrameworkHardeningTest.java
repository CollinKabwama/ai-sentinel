package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetExpectedClass;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;
import dev.aisentinel.core.replay.ReplayException;
import dev.aisentinel.core.replay.ReplayRunManifest;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-stage hardening and framework-acceptance coverage for the complete offline evaluation path.
 * <p>
 * Assertions here are structural (determinism, isolation, provenance, privacy, count reconciliation).
 * They are not detection-quality gates.
 */
class DetectionEvaluationFrameworkHardeningTest {

    private static final DetectionClassificationConfiguration THRESHOLD_A =
        new DetectionClassificationConfiguration(0.5);
    private static final DetectionClassificationConfiguration THRESHOLD_B =
        new DetectionClassificationConfiguration(0.75);
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void completeReferenceRunIsDeterministicAcrossRepeatedRunsAndOutputPaths() throws Exception {
        Path out1 = tempDir.resolve("complete-one");
        Path out2 = tempDir.resolve("nested").resolve("complete-two");

        DetectionEvaluationRunner.DetectionEvaluationRun first = evaluate(out1, THRESHOLD_A);
        DetectionEvaluationRunner.DetectionEvaluationRun second = evaluate(out2, THRESHOLD_A);

        assertStructuralCorpusExpectations(first.evidence());
        assertStructuralCorpusExpectations(second.evidence());

        assertThat(first.evidence()).isEqualTo(second.evidence());
        assertThat(first.metrics()).isEqualTo(second.metrics());
        assertThat(first.temporal()).isEqualTo(second.temporal());
        assertThat(first.alignment().evaluableObservationCount())
            .isEqualTo(second.alignment().evaluableObservationCount());
        assertThat(first.replayResultsSha256()).isEqualTo(second.replayResultsSha256());
        assertThat(first.replayConfigurationFingerprint())
            .isEqualTo(second.replayConfigurationFingerprint());
        assertThat(first.writtenEvidence().jsonSha256())
            .isEqualTo(second.writtenEvidence().jsonSha256());
        assertThat(first.writtenEvidence().markdownSha256())
            .isEqualTo(second.writtenEvidence().markdownSha256());

        byte[] json1 = Files.readAllBytes(out1.resolve("evaluation.json"));
        byte[] json2 = Files.readAllBytes(out2.resolve("evaluation.json"));
        byte[] md1 = Files.readAllBytes(out1.resolve("evaluation.md"));
        byte[] md2 = Files.readAllBytes(out2.resolve("evaluation.md"));
        assertThat(json1).containsExactly(json2);
        assertThat(md1).containsExactly(md2);
        assertThat(TrainingFingerprintHashes.sha256HexBytes(json1)).isEqualTo(first.writtenEvidence().jsonSha256());
        assertThat(TrainingFingerprintHashes.sha256HexBytes(md1)).isEqualTo(first.writtenEvidence().markdownSha256());

        String jsonText = new String(json1, StandardCharsets.UTF_8);
        String markdown = new String(md1, StandardCharsets.UTF_8);
        assertThat(jsonText).doesNotContain(out1.toAbsolutePath().normalize().toString());
        assertThat(jsonText).doesNotContain(out2.toAbsolutePath().normalize().toString());
        assertThat(markdown).doesNotContain(out1.toAbsolutePath().normalize().toString());
        assertThat(markdown).doesNotContain(out2.toAbsolutePath().normalize().toString());
        assertThat(jsonText).doesNotContain(System.getProperty("user.home"));
        for (String marker : List.of(
            "Authorization", "Bearer ", "cookie", "FeatureSnapshot",
            "requestsPerWindow", "endpointEntropy", "id:synthetic-"
        )) {
            assertThat(jsonText).doesNotContainIgnoringCase(marker);
            assertThat(markdown).doesNotContainIgnoringCase(marker);
        }
    }

    @Test
    void configurationBIsUnaffectedByPriorConfigurationA() throws Exception {
        Path afterA = tempDir.resolve("after-a");
        Path isolatedB = tempDir.resolve("isolated-b");

        evaluate(tempDir.resolve("config-a"), THRESHOLD_A);
        DetectionEvaluationRunner.DetectionEvaluationRun bAfterA = evaluate(afterA, THRESHOLD_B);
        DetectionEvaluationRunner.DetectionEvaluationRun bIsolated = evaluate(isolatedB, THRESHOLD_B);

        assertThat(bAfterA.evidence()).isEqualTo(bIsolated.evidence());
        assertThat(Files.readAllBytes(afterA.resolve("evaluation.json")))
            .containsExactly(Files.readAllBytes(isolatedB.resolve("evaluation.json")));
        assertThat(Files.readAllBytes(afterA.resolve("evaluation.md")))
            .containsExactly(Files.readAllBytes(isolatedB.resolve("evaluation.md")));
        assertThat(bAfterA.evidence().classification().anomalyThreshold()).isEqualTo(0.75);
    }

    @Test
    void failedRunDoesNotPoisonSubsequentValidRun() throws Exception {
        Path poisonedAttempt = tempDir.resolve("failed-attempt");
        Path validAfterFailure = tempDir.resolve("valid-after-failure");
        Path isolatedValid = tempDir.resolve("isolated-valid");

        assertThatThrownBy(() -> new DetectionEvaluationRunner().evaluate(
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            tempDir.resolve("missing-annotations.json"),
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD_A,
            poisonedAttempt
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("annotationsFile");

        DetectionEvaluationRunner.DetectionEvaluationRun afterFailure = evaluate(validAfterFailure, THRESHOLD_A);
        DetectionEvaluationRunner.DetectionEvaluationRun isolated = evaluate(isolatedValid, THRESHOLD_A);

        assertThat(afterFailure.evidence()).isEqualTo(isolated.evidence());
        assertThat(Files.readAllBytes(validAfterFailure.resolve("evaluation.json")))
            .containsExactly(Files.readAllBytes(isolatedValid.resolve("evaluation.json")));
        assertThat(Files.exists(poisonedAttempt)).isFalse();
    }

    @Test
    void rejectsMismatchedDatasetAnnotationsAtLoadBoundary() throws Exception {
        Path datasetDirectory = locateTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY);
        Path annotationsSource = locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE);
        Path mismatchedAnnotations = tempDir.resolve("mismatched-annotations.json");
        String mutated = Files.readString(annotationsSource, StandardCharsets.UTF_8)
            .replaceFirst("\"datasetId\"\\s*:\\s*\"[^\"]+\"", "\"datasetId\":\"other-dataset-id\"");
        Files.writeString(mismatchedAnnotations, mutated, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new DetectionEvaluationRunner().evaluate(
            datasetDirectory,
            mismatchedAnnotations,
            ReplayConfiguration.referenceDefaults(),
            THRESHOLD_A,
            tempDir.resolve("mismatch-out")
        )).isInstanceOf(ReplayException.class)
            .hasMessageContaining("datasetId");
    }

    @Test
    void evidenceGeneratorRejectsMetricsTemporalThresholdProvenanceMismatch() throws Exception {
        DetectionEvaluationRunner.DetectionEvaluationRun run =
            evaluate(tempDir.resolve("threshold-mismatch-base"), THRESHOLD_A);
        TemporalDetectionEvaluation temporalAtB =
            new TemporalDetectionEvaluator().evaluate(run.alignment(), THRESHOLD_B);

        assertThatThrownBy(() -> new DetectionEvaluationEvidenceGenerator().generate(
            loadDataset(),
            run.alignment(),
            reconstructManifest(run.evidence()),
            run.metrics(),
            temporalAtB,
            THRESHOLD_A
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold");
    }

    @Test
    void evidenceGeneratorRejectsExplicitClassificationClaimingDifferentThreshold() throws Exception {
        DetectionEvaluationRunner.DetectionEvaluationRun run =
            evaluate(tempDir.resolve("claim-threshold"), THRESHOLD_A);

        assertThatThrownBy(() -> new DetectionEvaluationEvidenceGenerator().generate(
            loadDataset(),
            run.alignment(),
            reconstructManifest(run.evidence()),
            run.metrics(),
            run.temporal(),
            THRESHOLD_B
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold");
    }

    @Test
    void historicalEvidenceGeneratorApiUsesMetricsClassificationWithoutHiddenDefault() throws Exception {
        DetectionEvaluationRunner.DetectionEvaluationRun run =
            evaluate(tempDir.resolve("historical-generator-api"), THRESHOLD_B);

        DetectionEvaluationEvidence evidence = new DetectionEvaluationEvidenceGenerator().generate(
            loadDataset(),
            run.alignment(),
            reconstructManifest(run.evidence()),
            run.metrics(),
            run.temporal()
        );

        assertThat(evidence.classification().anomalyThreshold()).isEqualTo(0.75);
        assertThat(evidence.classification().anomalyThreshold())
            .isEqualTo(run.metrics().classification().anomalyThreshold());
    }

    @Test
    void detectionEvaluationRunRejectsContradictoryPublicConstruction() throws Exception {
        DetectionEvaluationRunner.DetectionEvaluationRun run =
            evaluate(tempDir.resolve("run-contract-base"), THRESHOLD_A);
        DetectionEvaluationRunner.DetectionEvaluationRun other =
            evaluate(tempDir.resolve("run-contract-other"), THRESHOLD_B);

        assertThatThrownBy(() -> new DetectionEvaluationRunner.DetectionEvaluationRun(
            run.alignment(),
            run.metrics(),
            other.temporal(),
            run.evidence(),
            run.writtenEvidence(),
            run.replayResultsSha256(),
            run.replayConfigurationFingerprint()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold");

        assertThatThrownBy(() -> new DetectionEvaluationRunner.DetectionEvaluationRun(
            run.alignment(),
            run.metrics(),
            run.temporal(),
            run.evidence(),
            run.writtenEvidence(),
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            run.replayConfigurationFingerprint()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("replayResultsSha256");
    }

    @Test
    void evidenceRejectsScenarioCategoryDriftBetweenMetricsAndTemporal() throws Exception {
        DetectionEvaluationEvidence evidence =
            evaluate(tempDir.resolve("scenario-drift-base"), THRESHOLD_A).evidence();
        List<ScenarioTemporalEvaluation> driftedScenarios = new ArrayList<>();
        ScenarioTemporalEvaluation first = evidence.temporal().scenarios().get(0);
        ReferenceDatasetScenarioCategory otherCategory = first.scenarioCategory()
            == ReferenceDatasetScenarioCategory.WARMUP_NEW_IDENTITY
            ? ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE
            : ReferenceDatasetScenarioCategory.WARMUP_NEW_IDENTITY;
        driftedScenarios.add(new ScenarioTemporalEvaluation(
            first.scenarioId(),
            otherCategory,
            first.observationCount(),
            first.anomalySegments()
        ));
        driftedScenarios.addAll(evidence.temporal().scenarios().subList(1, evidence.temporal().scenarios().size()));
        TemporalDetectionEvaluation drifted = new TemporalDetectionEvaluation(
            evidence.temporal().datasetId(),
            evidence.temporal().replayRunId(),
            evidence.temporal().classification(),
            driftedScenarios
        );

        assertThatThrownBy(() -> new DetectionEvaluationEvidence(
            evidence.evidenceSchemaVersion(),
            evidence.reportKind(),
            evidence.reference(),
            evidence.replay(),
            evidence.classification(),
            evidence.counts(),
            evidence.metrics(),
            drifted,
            evidence.limitations()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scenario categories must match");
    }

    @Test
    void metricsAndTemporalAgreeOnUnavailableEvidenceAndExactThresholdBoundary() {
        DetectionClassificationConfiguration exact = new DetectionClassificationConfiguration(0.5);
        assertThat(exact.isPredictedAnomalous(0.5)).isTrue();
        assertThat(exact.isPredictedAnomalous(0.499999999)).isFalse();

        ReferenceEvaluationAlignment alignment = new ReferenceEvaluationAlignment(
            "dataset-unavailable",
            "replay-aaaaaaaaaaaaaaaa",
            3,
            1,
            3,
            List.of(
                fixtureObservation("evt-1", false, 0.5),
                fixtureObservation("evt-2", true, null),
                fixtureObservation("evt-3", true, 0.4)
            )
        );
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment, exact);
        TemporalDetectionEvaluation temporal = new TemporalDetectionEvaluator().evaluate(alignment, exact);

        assertThat(metrics.excludedPredictionCount()).isEqualTo(1);
        assertThat(metrics.evaluablePredictionCount()).isEqualTo(2);
        assertThat(metrics.confusionMatrix().truePositives()
            + metrics.confusionMatrix().trueNegatives()
            + metrics.confusionMatrix().falsePositives()
            + metrics.confusionMatrix().falseNegatives())
            .isEqualTo(metrics.evaluablePredictionCount());
        assertThat(temporal.classification()).isEqualTo(metrics.classification());
        assertThat(temporal.scenarios()).hasSize(1);
    }

    @Test
    void zeroRecoveryCorpusRemainsValidWithoutFabricatedRecovery() throws Exception {
        DetectionEvaluationEvidence evidence = evaluate(tempDir.resolve("zero-recovery"), THRESHOLD_A).evidence();

        assertThat(evidence.counts().recoveryWindowCount()).isZero();
        assertThat(evidence.counts().stabilizedRecoveryCount()).isZero();
        assertThat(evidence.counts().unstabilizedRecoveryCount()).isZero();
        assertThat(evidence.limitations())
            .contains("No observed recovery windows are present in this evaluation corpus.");
        assertThat(evidence.counts().anomalySegmentCount())
            .isEqualTo(evidence.counts().detectedSegmentCount() + evidence.counts().undetectedSegmentCount());
    }

    @Test
    void structuralCountsReconcileAcrossCompleteRun() throws Exception {
        DetectionEvaluationRunner.DetectionEvaluationRun run = evaluate(tempDir.resolve("counts"), THRESHOLD_A);
        DetectionEvaluationEvidence evidence = run.evidence();
        DetectionEvaluationMetrics metrics = run.metrics();

        assertThat(evidence.counts().alignedObservationCount())
            .isEqualTo(evidence.counts().expectedNormalObservationCount()
                + evidence.counts().expectedAnomalousObservationCount());
        assertThat(evidence.counts().alignedObservationCount())
            .isEqualTo(evidence.counts().evaluablePredictionCount()
                + evidence.counts().excludedPredictionCount());
        assertThat(metrics.evaluablePredictionCount())
            .isEqualTo(metrics.confusionMatrix().truePositives()
                + metrics.confusionMatrix().trueNegatives()
                + metrics.confusionMatrix().falsePositives()
                + metrics.confusionMatrix().falseNegatives());
        assertThat(evidence.counts().anomalySegmentCount())
            .isEqualTo(evidence.counts().detectedSegmentCount()
                + evidence.counts().undetectedSegmentCount());
        assertThat(evidence.counts().recoveryWindowCount())
            .isEqualTo(evidence.counts().stabilizedRecoveryCount()
                + evidence.counts().unstabilizedRecoveryCount());
        assertThat(evidence.metrics().scenarios()).hasSameSizeAs(evidence.temporal().scenarios());
        for (int i = 0; i < evidence.metrics().scenarios().size(); i++) {
            assertThat(evidence.metrics().scenarios().get(i).scenarioId())
                .isEqualTo(evidence.temporal().scenarios().get(i).scenarioId());
            assertThat(evidence.metrics().scenarios().get(i).scenarioCategory())
                .isEqualTo(evidence.temporal().scenarios().get(i).scenarioCategory());
        }
    }

    @Test
    void cliRejectsBlankUnknownAndPositionalArgumentsWithoutHiddenThreshold() {
        assertThatThrownBy(() -> ReferenceDetectionEvaluationEvidenceMain.Arguments.parse(new String[] {
            "--threshold", "   "
        })).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threshold");
        assertThatThrownBy(() -> ReferenceDetectionEvaluationEvidenceMain.Arguments.parse(new String[] {
            "--threshold", "0.5",
            "--unknown"
        })).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> ReferenceDetectionEvaluationEvidenceMain.Arguments.parse(new String[] {
            "positional",
            "--threshold", "0.5"
        })).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsupported");
        assertThatThrownBy(() -> ReferenceDetectionEvaluationEvidenceMain.Arguments.parse(new String[] {
            "--output"
        })).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--output");
        assertThatThrownBy(() -> ReferenceDetectionEvaluationEvidenceMain.Arguments.parse(new String[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing required --threshold");
    }

    @Test
    void evidenceWriterFailureDoesNotPublishPartialSuccessDirectory() throws Exception {
        DetectionEvaluationEvidence evidence = evaluate(tempDir.resolve("writer-source"), THRESHOLD_A).evidence();
        Path occupied = tempDir.resolve("occupied-destination");
        Files.createDirectories(occupied);
        Files.writeString(occupied.resolve("blocker.txt"), "exists", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new DetectionEvaluationEvidenceWriter().write(occupied, evidence))
            .isInstanceOf(Exception.class);
        assertThat(Files.exists(occupied.resolve("evaluation.json"))).isFalse();
        assertThat(Files.exists(occupied.resolve("evaluation.md"))).isFalse();
    }

    private DetectionEvaluationRunner.DetectionEvaluationRun evaluate(
        Path output,
        DetectionClassificationConfiguration classification
    ) throws Exception {
        return new DetectionEvaluationRunner().evaluate(
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE),
            ReplayConfiguration.referenceDefaults(),
            classification,
            output
        );
    }

    private static void assertStructuralCorpusExpectations(DetectionEvaluationEvidence evidence) {
        assertThat(evidence.counts().referenceEventCount()).isEqualTo(136);
        assertThat(evidence.counts().scenarioCount()).isEqualTo(11);
        assertThat(evidence.counts().alignedObservationCount()).isEqualTo(84);
        assertThat(evidence.counts().expectedNormalObservationCount()).isEqualTo(26);
        assertThat(evidence.counts().expectedAnomalousObservationCount()).isEqualTo(58);
        assertThat(evidence.counts().recoveryWindowCount()).isZero();
        assertThat(evidence.reportKind()).isEqualTo("diagnostic-detection-evaluation");
        assertThat(evidence.limitations()).anyMatch(text -> text.contains("REPORT != BASELINE"));
    }

    private static Path locateTrackedPath(Path relativePath) {
        Path candidate = relativePath.toAbsolutePath().normalize();
        if (Files.exists(candidate)) {
            return candidate;
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            candidate = current.resolve(relativePath).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("tracked path not found: " + relativePath);
    }

    private static EvaluationObservation fixtureObservation(String eventId,
                                                            boolean anomalousExpected,
                                                            Double anomalyScore) {
        int sequence = Integer.parseInt(eventId.substring(4));
        return new EvaluationObservation(
            eventId,
            "scenario-1",
            ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
            sequence,
            T0.plusSeconds(sequence),
            "identity-hidden",
            new EvaluationTruth(
                anomalousExpected
                    ? ReferenceDatasetExpectedClass.SYNTHETIC_ANOMALOUS
                    : ReferenceDatasetExpectedClass.NORMAL,
                anomalousExpected,
                false
            ),
            new EvaluationPrediction(
                EvaluationPredictionSource.REPLAY_SCORE,
                anomalyScore,
                null,
                EnforcementAction.ALLOW,
                List.of()
            )
        );
    }

    private static ReplayDataset loadDataset() throws Exception {
        return new ReplayDatasetLoader().load(
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE)
        );
    }

    private static ReplayRunManifest reconstructManifest(DetectionEvaluationEvidence evidence) {
        return new ReplayRunManifest(
            evidence.replay().replaySchemaVersion(),
            evidence.replay().replayRunId(),
            evidence.replay().datasetId(),
            evidence.reference().eventsSha256(),
            evidence.reference().datasetSchemaVersion(),
            evidence.reference().evaluationEventSchemaVersion(),
            evidence.reference().featureSchemaVersion(),
            evidence.reference().annotationSchemaVersion(),
            evidence.replay().scorerId(),
            evidence.replay().scorerVersion(),
            evidence.replay().policyId(),
            evidence.replay().policyVersion(),
            evidence.replay().configurationFingerprint(),
            evidence.replay().replayMode(),
            evidence.reference().ordering(),
            evidence.counts().referenceEventCount(),
            evidence.replay().aiSentinelVersion(),
            "results.jsonl",
            evidence.replay().resultsSha256()
        );
    }
}
