package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.EvaluationDatasetManifest;
import dev.aisentinel.core.dataset.EvaluationDatasetSchemas;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotationsLoader;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetExpectedClass;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetScenarioCategory;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;
import dev.aisentinel.core.replay.ReplayEngine;
import dev.aisentinel.core.replay.ReplayRunManifest;
import dev.aisentinel.core.replay.ReplaySchemas;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DetectionEvaluationEvidenceTest {
    private static final DetectionClassificationConfiguration THRESHOLD =
        new DetectionClassificationConfiguration(0.5);
    private static final String SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void generatedEvidenceRetainsExplicitThresholdAndStructuralCounts() {
        DetectionEvaluationEvidence evidence = generatedEvidence();

        assertThat(evidence.evidenceSchemaVersion()).isEqualTo("1");
        assertThat(evidence.reportKind()).isEqualTo("diagnostic-detection-evaluation");
        assertThat(evidence.classification().anomalyThreshold()).isEqualTo(0.5);
        assertThat(evidence.classification().thresholdBoundary())
            .isEqualTo("valid anomalyScore >= anomalyThreshold");
        assertThat(evidence.counts().referenceEventCount()).isEqualTo(4);
        assertThat(evidence.counts().scenarioCount()).isEqualTo(2);
        assertThat(evidence.counts().alignedObservationCount()).isEqualTo(4);
        assertThat(evidence.counts().expectedNormalObservationCount()).isEqualTo(2);
        assertThat(evidence.counts().expectedAnomalousObservationCount()).isEqualTo(2);
        assertThat(evidence.counts().evaluablePredictionCount()).isEqualTo(3);
        assertThat(evidence.counts().excludedPredictionCount()).isEqualTo(1);
        assertThat(evidence.counts().anomalySegmentCount()).isEqualTo(1);
        assertThat(evidence.counts().detectedSegmentCount()).isEqualTo(1);
        assertThat(evidence.counts().undetectedSegmentCount()).isZero();
        assertThat(evidence.counts().recoveryWindowCount()).isZero();
        assertThat(evidence.counts().stabilizedRecoveryCount()).isZero();
        assertThat(evidence.counts().unstabilizedRecoveryCount()).isZero();
    }

    @Test
    void evidenceRejectsContradictoryCounts() {
        DetectionEvaluationEvidence evidence = sampleEvidence();

        assertThatThrownBy(() -> new DetectionEvaluationEvidence(
            evidence.evidenceSchemaVersion(),
            evidence.reportKind(),
            evidence.reference(),
            evidence.replay(),
            evidence.classification(),
            new DetectionEvaluationEvidence.StructuralCounts(4, 2, 4, 2, 2, 2, 2, 2, 1, 1, 1, 1, 0),
            evidence.metrics(),
            evidence.temporal(),
            evidence.limitations()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("prediction counts must match metrics");
    }

    @Test
    void evidenceRejectsTemporalCountMismatch() {
        DetectionEvaluationEvidence evidence = sampleEvidence();

        assertThatThrownBy(() -> new DetectionEvaluationEvidence(
            evidence.evidenceSchemaVersion(),
            evidence.reportKind(),
            evidence.reference(),
            evidence.replay(),
            evidence.classification(),
            new DetectionEvaluationEvidence.StructuralCounts(4, 2, 4, 2, 2, 3, 1, 1, 1, 0, 1, 1, 0),
            evidence.metrics(),
            evidence.temporal(),
            evidence.limitations()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("temporal segment counts must match temporal evidence");
    }

    @Test
    void evidenceRejectsScenarioCategoryMismatchAcrossMetricsAndTemporal() {
        DetectionEvaluationEvidence evidence = sampleEvidence();
        TemporalDetectionEvaluation mismatchedTemporal = new TemporalDetectionEvaluation(
            evidence.temporal().datasetId(),
            evidence.temporal().replayRunId(),
            evidence.temporal().classification(),
            List.of(
                new ScenarioTemporalEvaluation(
                    "scenario-normal",
                    ReferenceDatasetScenarioCategory.WARMUP_NEW_IDENTITY,
                    2,
                    List.of()
                ),
                evidence.temporal().scenarios().get(1)
            )
        );

        assertThatThrownBy(() -> new DetectionEvaluationEvidence(
            evidence.evidenceSchemaVersion(),
            evidence.reportKind(),
            evidence.reference(),
            evidence.replay(),
            evidence.classification(),
            evidence.counts(),
            evidence.metrics(),
            mismatchedTemporal,
            evidence.limitations()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scenario categories must match");
    }

    @Test
    void canonicalJsonIncludesDeterministicSectionsAndOrdering() {
        String json = DetectionEvaluationEvidenceJson.write(sampleEvidence());

        assertThat(json).contains("\"evidenceSchemaVersion\":\"1\"");
        assertThat(json.indexOf("\"reference\":")).isLessThan(json.indexOf("\"replay\":"));
        assertThat(json.indexOf("\"replay\":")).isLessThan(json.indexOf("\"classification\":"));
        assertThat(json.indexOf("\"classification\":")).isLessThan(json.indexOf("\"counts\":"));
        assertThat(json.indexOf("\"counts\":")).isLessThan(json.indexOf("\"confusionMatrix\":"));
        assertThat(json.indexOf("\"confusionMatrix\":")).isLessThan(json.indexOf("\"metrics\":"));
        assertThat(json.indexOf("\"metrics\":")).isLessThan(json.indexOf("\"scenarioMetrics\":"));
        assertThat(json.indexOf("\"scenarioMetrics\":")).isLessThan(json.indexOf("\"temporal\":"));
        assertThat(json).contains("\"scenarioId\":\"scenario-normal\",\"scenarioCategory\":\"ESTABLISHED_NORMAL_BASELINE\"");
        assertThat(json).contains("\"scenarioId\":\"scenario-legitimate\",\"scenarioCategory\":\"LEGITIMATE_BULK_OPERATION\"");
        assertThat(json.indexOf("\"scenarioId\":\"scenario-normal\""))
            .isLessThan(json.indexOf("\"scenarioId\":\"scenario-legitimate\""));
    }

    @Test
    void canonicalJsonPreservesUndefinedMetricsWithoutNanOrInfinity() {
        String json = DetectionEvaluationEvidenceJson.write(sampleEvidenceWithUndefinedMetrics());

        assertThat(json).contains("\"precision\":{\"defined\":false,\"value\":null}");
        assertThat(json).contains("\"recall\":{\"defined\":false,\"value\":null}");
        assertThat(json.toLowerCase()).doesNotContain("nan");
        assertThat(json.toLowerCase()).doesNotContain("infinity");
    }

    @Test
    void canonicalJsonPreservesTemporalSegmentOrdering() {
        String json = DetectionEvaluationEvidenceJson.write(sampleEvidence());

        assertThat(json.indexOf("\"segmentIndex\":0")).isLessThan(json.indexOf("\"segmentIndex\":1"));
    }

    @Test
    void canonicalMarkdownShowsThresholdCountsMetricsAndUndefinedValues() {
        String markdown = DetectionEvaluationEvidenceMarkdown.write(sampleEvidenceWithUndefinedMetrics());

        assertThat(markdown).contains("Threshold: `0.5`");
        assertThat(markdown).contains("Aligned evaluation observations: `2`");
        assertThat(markdown).contains("True positives: `0`");
        assertThat(markdown).contains("Precision: `undefined`");
        assertThat(markdown).contains("False-positive rate: `undefined`");
    }

    @Test
    void canonicalMarkdownEscapesMarkdownSensitiveEvidenceStrings() {
        String markdown = DetectionEvaluationEvidenceMarkdown.write(sampleEvidenceWithMarkdownSensitiveStrings());

        assertThat(markdown).contains("### `scenario\\|\\`one\\ntwo`");
        assertThat(markdown).contains("- Source classification: `source\\|\\`star*underscore_<>\\nnext`");
        assertThat(markdown).contains("- Anomaly onset event: `evt\\|\\`a\\nb`");
        assertThat(markdown).contains("- limitation\\nnext");
        assertThat(markdown).doesNotContain("scenario|`one\ntwo");
    }

    @Test
    void canonicalMarkdownShowsDetectedUndetectedAndRecoveryAbsenceConservatively() {
        String markdown = DetectionEvaluationEvidenceMarkdown.write(sampleEvidence());

        assertThat(markdown).contains("Detected within observed anomaly window: `yes`");
        assertThat(markdown).contains("Detected within observed anomaly window: `no`");
        assertThat(markdown).contains("Observed recovery window: present");
        assertThat(markdown).contains("Observed recovery window: none");
        assertThat(markdown).contains("not detected within the observed anomaly window");
    }

    @Test
    void canonicalMarkdownUsesAnomalyFocusedLanguageWithoutProductionClaims() {
        String markdown = DetectionEvaluationEvidenceMarkdown.write(sampleEvidence());

        assertThat(markdown).contains("This report records deterministic diagnostic evaluation evidence.");
        assertThat(markdown).contains("official detection baseline");
        assertThat(markdown.toLowerCase()).doesNotContain("production efficacy");
        assertThat(markdown.toLowerCase()).doesNotContain("attack detected");
    }

    @Test
    void writerCreatesOutputDirectoryWithUtf8ArtifactsAndFinalNewlines() throws Exception {
        Path output = tempDir.resolve("evidence-output");
        DetectionEvaluationEvidenceWriter.WrittenEvidence written =
            new DetectionEvaluationEvidenceWriter().write(output, sampleEvidence());

        assertThat(Files.isDirectory(output)).isTrue();
        assertThat(written.outputDirectory()).isEqualTo(output.toAbsolutePath().normalize());
        assertThat(Files.readString(output.resolve("evaluation.json"), StandardCharsets.UTF_8)).endsWith("\n");
        assertThat(Files.readString(output.resolve("evaluation.md"), StandardCharsets.UTF_8)).endsWith("\n");
        assertThat(written.jsonSha256()).matches("[0-9a-f]{64}");
        assertThat(written.markdownSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void writerReturnsHashesAndByteCountsForExactWrittenBytes() throws Exception {
        Path output = tempDir.resolve("hash-output");

        DetectionEvaluationEvidenceWriter.WrittenEvidence written =
            new DetectionEvaluationEvidenceWriter().write(output, sampleEvidence());

        byte[] jsonBytes = Files.readAllBytes(output.resolve("evaluation.json"));
        byte[] markdownBytes = Files.readAllBytes(output.resolve("evaluation.md"));
        assertThat(written.jsonBytes()).isEqualTo(jsonBytes.length);
        assertThat(written.markdownBytes()).isEqualTo(markdownBytes.length);
        assertThat(Files.size(output.resolve("evaluation.json"))).isEqualTo(jsonBytes.length);
        assertThat(Files.size(output.resolve("evaluation.md"))).isEqualTo(markdownBytes.length);
        assertThat(written.jsonSha256()).isEqualTo(TrainingFingerprintHashes.sha256HexBytes(jsonBytes));
        assertThat(written.markdownSha256()).isEqualTo(TrainingFingerprintHashes.sha256HexBytes(markdownBytes));
    }

    @Test
    void writerRejectsExistingOutputDirectoryWithoutOverwrite() throws Exception {
        Path output = tempDir.resolve("existing-output");
        Files.createDirectories(output);

        assertThatThrownBy(() -> new DetectionEvaluationEvidenceWriter().write(output, sampleEvidence()))
            .isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void writerRejectsInvalidDestinationWhenParentIsAFile() throws Exception {
        Path parentFile = tempDir.resolve("not-a-directory");
        Files.writeString(parentFile, "x", StandardCharsets.UTF_8);
        Path output = parentFile.resolve("child");

        assertThatThrownBy(() -> new DetectionEvaluationEvidenceWriter().write(output, sampleEvidence()))
            .isInstanceOf(IOException.class);
    }

    @Test
    void repeatedGenerationProducesByteIdenticalArtifacts() throws Exception {
        DetectionEvaluationEvidence evidence = sampleEvidence();
        Path outA = tempDir.resolve("out-a");
        Path outB = tempDir.resolve("out-b");
        new DetectionEvaluationEvidenceWriter().write(outA, evidence);
        new DetectionEvaluationEvidenceWriter().write(outB, evidence);

        assertThat(Files.readAllBytes(outA.resolve("evaluation.json")))
            .containsExactly(Files.readAllBytes(outB.resolve("evaluation.json")));
        assertThat(Files.readAllBytes(outA.resolve("evaluation.md")))
            .containsExactly(Files.readAllBytes(outB.resolve("evaluation.md")));
    }

    @Test
    void destinationPathDoesNotAffectCanonicalContent() throws Exception {
        DetectionEvaluationEvidence evidence = sampleEvidence();
        Path first = tempDir.resolve("first-destination");
        Path second = tempDir.resolve("nested").resolve("second-destination");
        new DetectionEvaluationEvidenceWriter().write(first, evidence);
        new DetectionEvaluationEvidenceWriter().write(second, evidence);

        assertThat(Files.readString(first.resolve("evaluation.json"))).isEqualTo(Files.readString(second.resolve("evaluation.json")));
        assertThat(Files.readString(first.resolve("evaluation.md"))).isEqualTo(Files.readString(second.resolve("evaluation.md")));
    }

    @Test
    void privacySafeArtifactsExcludeIdentityEndpointsSecretsBodiesAndFeatureSnapshots() throws Exception {
        Path output = tempDir.resolve("privacy-output");
        new DetectionEvaluationEvidenceWriter().write(output, sampleEvidence());

        String json = Files.readString(output.resolve("evaluation.json"));
        String markdown = Files.readString(output.resolve("evaluation.md"));
        for (String marker : List.of("id:synthetic-", "route:/", "Authorization", "Bearer ", "cookie", "body", "FeatureSnapshot",
            "requestsPerWindow", "endpointEntropy", "endpointConcentration", "payloadSizeBytes")) {
            assertThat(json).doesNotContainIgnoringCase(marker);
            assertThat(markdown).doesNotContainIgnoringCase(marker);
        }
    }

    @Test
    void realPipelineReferenceCorpusProducesDeterministicEvidenceAndReconciledCounts() throws Exception {
        DetectionEvaluationEvidence evidence = generateReferenceEvidence(0.5);

        assertThat(evidence.reference().datasetId()).isEqualTo("reference-synthetic-evaluation-v1");
        assertThat(evidence.counts().referenceEventCount()).isEqualTo(136);
        assertThat(evidence.counts().scenarioCount()).isEqualTo(11);
        assertThat(evidence.counts().alignedObservationCount()).isEqualTo(84);
        assertThat(evidence.counts().expectedNormalObservationCount()).isEqualTo(26);
        assertThat(evidence.counts().expectedAnomalousObservationCount()).isEqualTo(58);
        assertThat(evidence.counts().alignedObservationCount())
            .isEqualTo((int) (evidence.counts().expectedNormalObservationCount()
                + evidence.counts().expectedAnomalousObservationCount()));
        assertThat(evidence.counts().recoveryWindowCount()).isZero();
        assertThat(evidence.limitations()).contains("No observed recovery windows are present in this evaluation corpus.");
    }

    @Test
    void realPipelineArtifactsAreByteIdenticalAcrossTwoDirectories() throws Exception {
        DetectionEvaluationEvidence evidence = generateReferenceEvidence(0.5);
        Path first = tempDir.resolve("reference-one");
        Path second = tempDir.resolve("reference-two");
        DetectionEvaluationEvidenceWriter writer = new DetectionEvaluationEvidenceWriter();
        writer.write(first, evidence);
        writer.write(second, evidence);

        assertThat(Files.readAllBytes(first.resolve("evaluation.json")))
            .containsExactly(Files.readAllBytes(second.resolve("evaluation.json")));
        assertThat(Files.readAllBytes(first.resolve("evaluation.md")))
            .containsExactly(Files.readAllBytes(second.resolve("evaluation.md")));
    }

    @Test
    void cliRequiresExplicitThreshold() {
        assertThatThrownBy(() -> ReferenceDetectionEvaluationEvidenceMain.main(new String[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--threshold");
    }

    @Test
    void cliRejectsInvalidThresholdsWithoutFallback() {
        for (String threshold : List.of("NaN", "Infinity", "-Infinity", "-0.1", "1.1", "not-a-number")) {
            assertThatThrownBy(() -> ReferenceDetectionEvaluationEvidenceMain.main(new String[] {
                "--output", tempDir.resolve("invalid-" + threshold.replace("-", "minus")).toString(),
                "--threshold", threshold
            })).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
        }
    }

    @Test
    void cliAcceptsThresholdBoundsAndRejectsDuplicateOrEmptyArguments() throws Exception {
        Path zero = tempDir.resolve("cli-threshold-zero");
        ReferenceDetectionEvaluationEvidenceMain.main(new String[] {"--output", zero.toString(), "--threshold", "0"});
        assertThat(Files.readString(zero.resolve("evaluation.json"))).contains("\"anomalyThreshold\":0.0");

        Path one = tempDir.resolve("cli-threshold-one");
        ReferenceDetectionEvaluationEvidenceMain.main(new String[] {"--output", one.toString(), "--threshold", "1"});
        assertThat(Files.readString(one.resolve("evaluation.json"))).contains("\"anomalyThreshold\":1.0");

        assertThatThrownBy(() -> ReferenceDetectionEvaluationEvidenceMain.main(new String[] {
            "--output", tempDir.resolve("duplicate").toString(),
            "--threshold", "0.5",
            "--threshold", "0.6"
        })).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate --threshold");
        assertThatThrownBy(() -> ReferenceDetectionEvaluationEvidenceMain.main(new String[] {
            "--output=",
            "--threshold", "0.5"
        })).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--output");
    }

    @Test
    void cliGeneratesEvidenceArtifactsWhenThresholdIsExplicit() throws Exception {
        Path output = tempDir.resolve("cli-evidence");

        ReferenceDetectionEvaluationEvidenceMain.main(new String[] {
            "--output", output.toString(),
            "--threshold", "0.5"
        });

        assertThat(Files.exists(output.resolve("evaluation.json"))).isTrue();
        assertThat(Files.exists(output.resolve("evaluation.md"))).isTrue();
        assertThat(Files.readString(output.resolve("evaluation.json"))).contains("\"anomalyThreshold\":0.5");
    }

    private DetectionEvaluationEvidence generatedEvidence() {
        ReplayDataset dataset = new ReplayDataset(
            new EvaluationDatasetManifest(
                EvaluationDatasetSchemas.DATASET_SCHEMA_VERSION,
                "dataset-1",
                T0,
                "0.3.0",
                "1",
                "1",
                4,
                EvaluationDatasetSchemas.ORDERING_APPEND_ORDER,
                "synthetic-reference-evaluation",
                "reference-dataset-v1",
                EvaluationDatasetSchemas.EVENTS_FILE_NAME,
                SHA_A,
                "sample dataset",
                "sample"
            ),
            SHA_A,
            List.of(),
            new ReplayDataset.AnnotationMetadata("1", "dataset-1", 2)
        );
        ReferenceEvaluationAlignment alignment = new ReferenceEvaluationAlignment(
            "dataset-1",
            "replay-1234567890abcdef",
            4,
            2,
            4,
            List.of(
                observation("evt-1", "scenario-normal", ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
                    ReferenceDatasetExpectedClass.NORMAL, false, 0.1, EnforcementAction.ALLOW, List.of(), 1, T0),
                observation("evt-2", "scenario-normal", ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
                    ReferenceDatasetExpectedClass.NORMAL, false, null, EnforcementAction.MONITOR, List.of(), 2, T0.plusSeconds(10)),
                observation("evt-3", "scenario-legitimate", ReferenceDatasetScenarioCategory.LEGITIMATE_BULK_OPERATION,
                    ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS, false, 0.8, EnforcementAction.BLOCK, List.of(), 3, T0.plusSeconds(20)),
                observation("evt-4", "scenario-legitimate", ReferenceDatasetScenarioCategory.LEGITIMATE_BULK_OPERATION,
                    ReferenceDatasetExpectedClass.LEGITIMATE_ANOMALOUS, false, 0.2, EnforcementAction.ALLOW, List.of(), 4, T0.plusSeconds(30))
            )
        );
        DetectionEvaluationMetrics metrics = new DetectionMetricsCalculator().compute(alignment, THRESHOLD);
        TemporalDetectionEvaluation temporal = new TemporalDetectionEvaluator().evaluate(alignment, THRESHOLD);
        ReplayRunManifest replayManifest = new ReplayRunManifest(
            ReplaySchemas.REPLAY_SCHEMA_VERSION,
            "replay-1234567890abcdef",
            "dataset-1",
            SHA_A,
            EvaluationDatasetSchemas.DATASET_SCHEMA_VERSION,
            "1",
            "1",
            "1",
            "statistical",
            "",
            "threshold-policy",
            "",
            SHA_B,
            "fresh-run",
            EvaluationDatasetSchemas.ORDERING_APPEND_ORDER,
            4,
            "0.3.0",
            "results.jsonl",
            SHA_B
        );
        return new DetectionEvaluationEvidenceGenerator().generate(dataset, alignment, replayManifest, metrics, temporal);
    }

    private DetectionEvaluationEvidence sampleEvidence() {
        DetectionConfusionMatrix confusion = new DetectionConfusionMatrix(1, 1, 0, 1);
        DetectionMetrics metrics = DetectionMetrics.from(confusion);
        return new DetectionEvaluationEvidence(
            "1",
            "diagnostic-detection-evaluation",
            new DetectionEvaluationEvidence.ReferenceProvenance(
                "dataset-1", "1", "1", "1", "1",
                "synthetic-reference-evaluation", "reference-dataset-v1", "append-order", SHA_A
            ),
            new DetectionEvaluationEvidence.ReplayProvenance(
                "1", "replay-1234567890abcdef", "dataset-1", "fresh-run",
                "statistical", "", "threshold-policy", "", SHA_B, "0.3.0", SHA_A
            ),
            new DetectionEvaluationEvidence.ClassificationProvenance(
                0.5, "valid anomalyScore >= anomalyThreshold"
            ),
            new DetectionEvaluationEvidence.StructuralCounts(4, 2, 4, 2, 2, 3, 1, 2, 1, 1, 1, 1, 0),
            new DetectionEvaluationMetrics(
                "dataset-1",
                "replay-1234567890abcdef",
                THRESHOLD,
                4,
                3,
                1,
                confusion,
                metrics,
                List.of(
                    new ScenarioDetectionMetrics(
                        "scenario-normal",
                        ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
                        2,
                        1,
                        1,
                        new DetectionConfusionMatrix(0, 1, 0, 0),
                        DetectionMetrics.from(new DetectionConfusionMatrix(0, 1, 0, 0))
                    ),
                    new ScenarioDetectionMetrics(
                        "scenario-legitimate",
                        ReferenceDatasetScenarioCategory.LEGITIMATE_BULK_OPERATION,
                        2,
                        2,
                        0,
                        new DetectionConfusionMatrix(1, 0, 0, 1),
                        DetectionMetrics.from(new DetectionConfusionMatrix(1, 0, 0, 1))
                    )
                )
            ),
            new TemporalDetectionEvaluation(
                "dataset-1",
                "replay-1234567890abcdef",
                THRESHOLD,
                List.of(
                    new ScenarioTemporalEvaluation(
                        "scenario-normal",
                        ReferenceDatasetScenarioCategory.ESTABLISHED_NORMAL_BASELINE,
                        2,
                        List.of()
                    ),
                    new ScenarioTemporalEvaluation(
                        "scenario-legitimate",
                        ReferenceDatasetScenarioCategory.LEGITIMATE_BULK_OPERATION,
                        2,
                        List.of(
                            new TemporalAnomalySegment(
                                0,
                                new TemporalObservationPoint("evt-3", 3, T0.plusSeconds(20)),
                                new TemporalObservationPoint("evt-4", 4, T0.plusSeconds(30)),
                                2,
                                true,
                                new TemporalObservationPoint("evt-3", 3, T0.plusSeconds(20)),
                                0,
                                0,
                                java.time.Duration.ZERO,
                                0,
                                new TemporalRecoveryEvaluation(
                                    new TemporalObservationPoint("evt-5", 5, T0.plusSeconds(40)),
                                    new TemporalObservationPoint("evt-6", 6, T0.plusSeconds(50)),
                                    2,
                                    true,
                                    new TemporalObservationPoint("evt-5", 5, T0.plusSeconds(40)),
                                    0,
                                    0,
                                    java.time.Duration.ZERO,
                                    0
                                )
                            ),
                            new TemporalAnomalySegment(
                                1,
                                new TemporalObservationPoint("evt-7", 7, T0.plusSeconds(60)),
                                new TemporalObservationPoint("evt-7", 7, T0.plusSeconds(60)),
                                1,
                                false,
                                null,
                                null,
                                null,
                                null,
                                1,
                                null
                            )
                        )
                    )
                )
            ),
            List.of(
                "REPORT != BASELINE. This artifact records deterministic diagnostic evaluation evidence only.",
                "POLICY ACTION != DETECTOR PREDICTION. Detector classification remains thresholded anomaly-score evaluation.",
                "DETECTION DELAY != REQUEST LATENCY. Temporal delay describes ordered evaluation observations, not application latency."
            )
        );
    }

    private DetectionEvaluationEvidence sampleEvidenceWithMarkdownSensitiveStrings() {
        String scenarioId = "scenario|`one\ntwo";
        String eventId = "evt|`a\nb";
        DetectionConfusionMatrix confusion = new DetectionConfusionMatrix(1, 0, 0, 0);
        DetectionMetrics metrics = DetectionMetrics.from(confusion);
        return new DetectionEvaluationEvidence(
            "1",
            "diagnostic-detection-evaluation",
            new DetectionEvaluationEvidence.ReferenceProvenance(
                "dataset-escaped", "1", "1", "1", "1",
                "source|`star*underscore_<>\nnext", "reference-dataset-v1", "append-order", SHA_A
            ),
            new DetectionEvaluationEvidence.ReplayProvenance(
                "1", "replay-escaped", "dataset-escaped", "fresh-run",
                "statistical", "", "threshold-policy", "", SHA_B, "0.3.0", SHA_A
            ),
            new DetectionEvaluationEvidence.ClassificationProvenance(
                0.5, "valid anomalyScore >= anomalyThreshold"
            ),
            new DetectionEvaluationEvidence.StructuralCounts(1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0),
            new DetectionEvaluationMetrics(
                "dataset-escaped",
                "replay-escaped",
                THRESHOLD,
                1,
                1,
                0,
                confusion,
                metrics,
                List.of(
                    new ScenarioDetectionMetrics(
                        scenarioId,
                        ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST,
                        1,
                        1,
                        0,
                        confusion,
                        metrics
                    )
                )
            ),
            new TemporalDetectionEvaluation(
                "dataset-escaped",
                "replay-escaped",
                THRESHOLD,
                List.of(
                    new ScenarioTemporalEvaluation(
                        scenarioId,
                        ReferenceDatasetScenarioCategory.RAPID_REQUEST_BURST,
                        1,
                        List.of(
                            new TemporalAnomalySegment(
                                0,
                                new TemporalObservationPoint(eventId, 1, T0),
                                new TemporalObservationPoint(eventId, 1, T0),
                                1,
                                true,
                                new TemporalObservationPoint(eventId, 1, T0),
                                0,
                                0,
                                java.time.Duration.ZERO,
                                0,
                                null
                            )
                        )
                    )
                )
            ),
            List.of("limitation\nnext")
        );
    }

    private DetectionEvaluationEvidence sampleEvidenceWithUndefinedMetrics() {
        DetectionConfusionMatrix confusion = new DetectionConfusionMatrix(0, 0, 0, 0);
        return new DetectionEvaluationEvidence(
            "1",
            "diagnostic-detection-evaluation",
            new DetectionEvaluationEvidence.ReferenceProvenance(
                "dataset-2", "1", "1", "1", "1",
                "synthetic-reference-evaluation", "reference-dataset-v1", "append-order", SHA_A
            ),
            new DetectionEvaluationEvidence.ReplayProvenance(
                "1", "replay-2222222222222222", "dataset-2", "fresh-run",
                "statistical", "", "threshold-policy", "", SHA_B, "0.3.0", SHA_A
            ),
            new DetectionEvaluationEvidence.ClassificationProvenance(
                0.5, "valid anomalyScore >= anomalyThreshold"
            ),
            new DetectionEvaluationEvidence.StructuralCounts(2, 1, 2, 1, 1, 0, 2, 1, 0, 1, 0, 0, 0),
            new DetectionEvaluationMetrics(
                "dataset-2",
                "replay-2222222222222222",
                THRESHOLD,
                2,
                0,
                2,
                confusion,
                DetectionMetrics.from(confusion),
                List.of(
                    new ScenarioDetectionMetrics(
                        "scenario-undefined",
                        ReferenceDatasetScenarioCategory.WARMUP_NEW_IDENTITY,
                        2,
                        0,
                        2,
                        confusion,
                        DetectionMetrics.from(confusion)
                    )
                )
            ),
            new TemporalDetectionEvaluation(
                "dataset-2",
                "replay-2222222222222222",
                THRESHOLD,
                List.of(
                    new ScenarioTemporalEvaluation(
                        "scenario-undefined",
                        ReferenceDatasetScenarioCategory.WARMUP_NEW_IDENTITY,
                        2,
                        List.of(
                            new TemporalAnomalySegment(
                                0,
                                new TemporalObservationPoint("evt-u1", 1, T0),
                                new TemporalObservationPoint("evt-u2", 2, T0.plusSeconds(1)),
                                2,
                                false,
                                null,
                                null,
                                null,
                                null,
                                2,
                                null
                            )
                        )
                    )
                )
            ),
            List.of(
                "REPORT != BASELINE. This artifact records deterministic diagnostic evaluation evidence only.",
                "No observed recovery windows are present in this evaluation corpus."
            )
        );
    }

    private DetectionEvaluationEvidence generateReferenceEvidence(double threshold) throws Exception {
        ReplayDataset dataset = new ReplayDatasetLoader().load(
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE)
        );
        ReferenceDatasetAnnotations annotations = new ReferenceDatasetAnnotationsLoader()
            .load(locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE));
        ReplayConfiguration configuration = ReplayConfiguration.referenceDefaults();
        Path replayOutput = tempDir.resolve("replay-" + Long.toString(System.nanoTime()));
        ReplayEngine.ReplayRun run = new ReplayEngine().run(dataset, configuration, replayOutput);
        try {
            ReferenceEvaluationAlignment alignment =
                new ReferenceEvaluationAligner().align(dataset, annotations, run.results());
            DetectionClassificationConfiguration classification = new DetectionClassificationConfiguration(threshold);
            DetectionEvaluationMetrics metrics =
                new DetectionMetricsCalculator().compute(alignment, classification);
            TemporalDetectionEvaluation temporal =
                new TemporalDetectionEvaluator().evaluate(alignment, classification);
            return new DetectionEvaluationEvidenceGenerator()
                .generate(dataset, alignment, run.manifest(), metrics, temporal);
        } finally {
            deleteRecursively(replayOutput);
        }
    }

    private static EvaluationObservation observation(String eventId,
                                                     String scenarioId,
                                                     ReferenceDatasetScenarioCategory category,
                                                     ReferenceDatasetExpectedClass expectedClass,
                                                     boolean maliciousnessAsserted,
                                                     Double anomalyScore,
                                                     EnforcementAction action,
                                                     List<EvaluationStatus> statuses,
                                                     int sequence,
                                                     Instant observedAt) {
        return new EvaluationObservation(
            eventId,
            scenarioId,
            category,
            sequence,
            observedAt,
            "identity-hidden",
            new EvaluationTruth(expectedClass, expectedClass != ReferenceDatasetExpectedClass.NORMAL, maliciousnessAsserted),
            new EvaluationPrediction(EvaluationPredictionSource.REPLAY_SCORE, anomalyScore, null, action, statuses)
        );
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                .forEach(candidate -> {
                    try {
                        Files.deleteIfExists(candidate);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
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
        return relativePath.toAbsolutePath().normalize();
    }
}
