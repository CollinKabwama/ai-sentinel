package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotations;
import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Capture-integrity coverage for the Official Detection Reference Baseline.
 * <p>
 * Assertions are structural (determinism, provenance, privacy, refusal semantics).
 * They are not detection-quality gates.
 */
class DetectionReferenceBaselineCaptureTest {

    @TempDir
    Path tempDir;

    @Test
    void officialReferenceConfigurationBindsApprovedThresholdWithoutGeneralDefault() {
        DetectionReferenceBaselineConfiguration configuration =
            DetectionReferenceBaselineConfiguration.officialReference();

        assertThat(configuration.classification().anomalyThreshold()).isEqualTo(0.5);
        assertThat(DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD).isEqualTo(0.5);
        assertThat(configuration.expectedDatasetId()).isEqualTo(ReferenceDatasetGenerator.DATASET_ID);
        assertThat(configuration.expectedAnnotationSchemaVersion())
            .isEqualTo(ReferenceDatasetAnnotations.SCHEMA_VERSION);
        assertThat(configuration.replayConfiguration())
            .isEqualTo(ReplayConfiguration.referenceDefaults());

        assertThatThrownBy(() -> new DetectionReferenceBaselineConfiguration(
            new DetectionClassificationConfiguration(0.75),
            ReplayConfiguration.referenceDefaults(),
            ReferenceDatasetGenerator.DATASET_ID,
            ReferenceDatasetAnnotations.SCHEMA_VERSION,
            ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY,
            ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("0.5");
    }

    @Test
    void realReferenceCorpusCaptureIsDeterministicAndPathIndependent() throws Exception {
        Path outA = tempDir.resolve("capture-a");
        Path outB = tempDir.resolve("nested").resolve("capture-b");

        DetectionReferenceBaselineCaptureResult first =
            new DetectionReferenceBaselineCapture().capture(officialConfig(), outA);
        DetectionReferenceBaselineCaptureResult second =
            new DetectionReferenceBaselineCapture().capture(officialConfig(), outB);

        assertBaselineIdentity(first);
        assertBaselineIdentity(second);
        assertThat(first.manifest()).isEqualTo(second.manifest());
        assertThat(first.manifestSha256()).isEqualTo(second.manifestSha256());
        assertThat(first.evaluationJsonSha256()).isEqualTo(second.evaluationJsonSha256());
        assertThat(first.evaluationMarkdownSha256()).isEqualTo(second.evaluationMarkdownSha256());

        assertByteIdenticalArtifacts(outA, outB);
        assertPrivacyAndNoPathLeak(outA, outB);
        assertThresholdAndProvenance(first);
        assertRequiredLimitations(first.manifest().limitations());
    }

    @Test
    void refusesExistingDestinationWithoutOverwriteFlags() throws Exception {
        Path existing = tempDir.resolve("existing-baseline");
        new DetectionReferenceBaselineCapture().capture(officialConfig(), existing);

        assertThatThrownBy(() -> new DetectionReferenceBaselineCapture().capture(officialConfig(), existing))
            .isInstanceOf(FileAlreadyExistsException.class)
            .hasMessageContaining("already exists");

        byte[] manifestBefore = Files.readAllBytes(existing.resolve("manifest.json"));
        assertThatThrownBy(() -> new DetectionReferenceBaselineCapture().capture(officialConfig(), existing))
            .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(existing.resolve("manifest.json"))).containsExactly(manifestBefore);
    }

    @Test
    void failedCaptureDoesNotPoisonSubsequentValidCapture() throws Exception {
        Path failed = tempDir.resolve("failed-baseline");
        Path afterFailure = tempDir.resolve("after-failure");
        Path isolated = tempDir.resolve("isolated");

        DetectionReferenceBaselineConfiguration broken = new DetectionReferenceBaselineConfiguration(
            officialConfig().classification(),
            officialConfig().replayConfiguration(),
            officialConfig().expectedDatasetId(),
            officialConfig().expectedAnnotationSchemaVersion(),
            officialConfig().datasetDirectory(),
            tempDir.resolve("missing-annotations.json")
        );

        assertThatThrownBy(() -> new DetectionReferenceBaselineCapture().capture(broken, failed))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(failed)).isFalse();

        DetectionReferenceBaselineCaptureResult recovered =
            new DetectionReferenceBaselineCapture().capture(officialConfig(), afterFailure);
        DetectionReferenceBaselineCaptureResult control =
            new DetectionReferenceBaselineCapture().capture(officialConfig(), isolated);

        assertThat(recovered.manifestSha256()).isEqualTo(control.manifestSha256());
        assertByteIdenticalArtifacts(afterFailure, isolated);
    }

    @Test
    void validatorRejectsProvenanceAndHashMismatches() throws Exception {
        Path out = tempDir.resolve("validator-base");
        DetectionReferenceBaselineCaptureResult captured =
            new DetectionReferenceBaselineCapture().capture(officialConfig(), out);
        DetectionReferenceBaselineManifest manifest = captured.manifest();
        DetectionEvaluationEvidence evidence = readEvidence(out);
        String annotationsSha256 = manifest.annotationsSha256();
        DetectionReferenceBaselineValidator validator = new DetectionReferenceBaselineValidator();
        DetectionReferenceBaselineConfiguration configuration = officialConfig();

        validator.validate(
            manifest,
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        );

        assertThatThrownBy(() -> validator.validate(
            withDatasetId(manifest, "other-dataset"),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> validator.validate(
            withEventsHash(manifest, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reference provenance");

        assertThatThrownBy(() -> validator.validate(
            withAnnotationSchema(manifest, "999"),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> validator.validate(
            withAnnotationsHash(manifest, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("annotation content hash");

        assertThatThrownBy(() -> validator.validate(
            withReplayRunId(manifest, "other-run"),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("replay provenance");

        assertThatThrownBy(() -> validator.validate(
            withScorer(manifest, "other-scorer", manifest.replay().scorerVersion()),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("replay provenance");

        assertThatThrownBy(() -> validator.validate(
            withScorer(manifest, manifest.replay().scorerId(), "other-version"),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("replay provenance");

        assertThatThrownBy(() -> validator.validate(
            withPolicy(manifest, "other-policy", manifest.replay().policyVersion()),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("replay provenance");

        assertThatThrownBy(() -> validator.validate(
            withFingerprint(manifest, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> validator.validate(
            withResultsHash(manifest, "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("replay provenance");

        DetectionEvaluationEvidence wrongThresholdEvidence = new DetectionEvaluationEvidence(
            evidence.evidenceSchemaVersion(),
            evidence.reportKind(),
            evidence.reference(),
            evidence.replay(),
            new DetectionEvaluationEvidence.ClassificationProvenance(
                0.75,
                DetectionReferenceBaselineSchemas.THRESHOLD_BOUNDARY
            ),
            evidence.counts(),
            withMetricsThreshold(evidence.metrics(), 0.75),
            withTemporalThreshold(evidence.temporal(), 0.75),
            evidence.limitations()
        );
        assertThatThrownBy(() -> validator.validate(
            manifest,
            wrongThresholdEvidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("0.5");

        assertThatThrownBy(() -> validator.validate(
            withStructure(manifest, new DetectionEvaluationEvidence.StructuralCounts(
                evidence.counts().referenceEventCount() + 1,
                evidence.counts().scenarioCount(),
                evidence.counts().alignedObservationCount(),
                evidence.counts().expectedNormalObservationCount(),
                evidence.counts().expectedAnomalousObservationCount(),
                evidence.counts().evaluablePredictionCount(),
                evidence.counts().excludedPredictionCount(),
                evidence.counts().anomalySegmentCount(),
                evidence.counts().detectedSegmentCount(),
                evidence.counts().undetectedSegmentCount(),
                evidence.counts().recoveryWindowCount(),
                evidence.counts().stabilizedRecoveryCount(),
                evidence.counts().unstabilizedRecoveryCount()
            )),
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("structural");

        assertThatThrownBy(() -> validator.validate(
            manifest,
            evidence,
            configuration,
            annotationsSha256,
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("artifact hash");

        assertThatThrownBy(() -> validator.validate(
            manifest,
            evidence,
            configuration,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("artifact hash");

        DetectionReferenceBaselineConfiguration wrongDatasetExpectation =
            new DetectionReferenceBaselineConfiguration(
                configuration.classification(),
                configuration.replayConfiguration(),
                "other-dataset",
                configuration.expectedAnnotationSchemaVersion(),
                configuration.datasetDirectory(),
                configuration.annotationsFile()
            );
        assertThatThrownBy(() -> validator.validate(
            manifest,
            evidence,
            wrongDatasetExpectation,
            annotationsSha256,
            captured.evaluationJsonSha256(),
            captured.evaluationMarkdownSha256(),
            captured.evaluationJsonBytes(),
            captured.evaluationMarkdownBytes()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("dataset ID");
    }

    @Test
    void validatorRejectsTamperedPublishedEvaluationArtifacts() throws Exception {
        Path out = tempDir.resolve("tamper-artifacts");
        DetectionReferenceBaselineCaptureResult captured =
            new DetectionReferenceBaselineCapture().capture(officialConfig(), out);
        DetectionReferenceBaselineValidator validator = new DetectionReferenceBaselineValidator();

        validator.validatePublishedArtifacts(captured.manifest(), out);

        Path tamperedJson = tempDir.resolve("tampered-json");
        copyBaselineDirectory(out, tamperedJson);
        Files.writeString(
            tamperedJson.resolve("evaluation.json"),
            "\n",
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND
        );
        assertThatThrownBy(() -> validator.validatePublishedArtifacts(captured.manifest(), tamperedJson))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("artifact hash");

        Path tamperedMarkdown = tempDir.resolve("tampered-markdown");
        copyBaselineDirectory(out, tamperedMarkdown);
        Files.writeString(
            tamperedMarkdown.resolve("evaluation.md"),
            "\n",
            StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND
        );
        assertThatThrownBy(() -> validator.validatePublishedArtifacts(captured.manifest(), tamperedMarkdown))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("artifact hash");
    }

    @Test
    void annotationTruthMutationChangesBaselineProvenanceAndEvidence() throws Exception {
        Path mutatedAnnotations = tempDir.resolve("annotations-mutated.json");
        String source = Files.readString(
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE),
            StandardCharsets.UTF_8
        );
        String mutated = source.replaceFirst(
            "\"expectedClass\":\"NORMAL\",\"anomalyExpected\":false",
            "\"expectedClass\":\"SYNTHETIC_ANOMALOUS\",\"anomalyExpected\":true"
        );
        Files.writeString(mutatedAnnotations, mutated, StandardCharsets.UTF_8);

        DetectionReferenceBaselineConfiguration mutatedConfig =
            new DetectionReferenceBaselineConfiguration(
                officialConfig().classification(),
                officialConfig().replayConfiguration(),
                officialConfig().expectedDatasetId(),
                officialConfig().expectedAnnotationSchemaVersion(),
                officialConfig().datasetDirectory(),
                mutatedAnnotations
            );

        DetectionReferenceBaselineCaptureResult original =
            new DetectionReferenceBaselineCapture().capture(officialConfig(), tempDir.resolve("original-annotations"));
        DetectionReferenceBaselineCaptureResult mutatedCapture =
            new DetectionReferenceBaselineCapture().capture(mutatedConfig, tempDir.resolve("mutated-annotations"));

        assertThat(mutatedCapture.manifest().annotationsSha256())
            .isNotEqualTo(original.manifest().annotationsSha256());
        assertThat(mutatedCapture.evaluationJsonSha256())
            .isNotEqualTo(original.evaluationJsonSha256());
        assertThat(mutatedCapture.manifest().structure().expectedAnomalousObservationCount())
            .isGreaterThan(original.manifest().structure().expectedAnomalousObservationCount());
    }

    @Test
    void manifestSerializationIsDeterministic() throws Exception {
        Path out = tempDir.resolve("serialize");
        DetectionReferenceBaselineCaptureResult captured =
            new DetectionReferenceBaselineCapture().capture(officialConfig(), out);
        String once = DetectionReferenceBaselineJson.write(captured.manifest());
        String twice = DetectionReferenceBaselineJson.write(captured.manifest());
        assertThat(once).isEqualTo(twice);
        assertThat(Files.readString(out.resolve("manifest.json"), StandardCharsets.UTF_8))
            .isEqualTo(once + "\n");
        assertThat(once).contains("\"anomalyThreshold\":0.5");
        assertThat(once).contains("\"role\":\"reference-classification-threshold\"");
        assertThat(once).contains("\"thresholdBoundary\":\"valid anomalyScore >= anomalyThreshold\"");
        assertThat(once).doesNotContain("\"role\":\"optimal\"");
        assertThat(once).doesNotContain("\"role\":\"recommended\"");
        assertThat(once).doesNotContain("\"role\":\"production\"");
    }

    @Test
    void classificationConfigurationIsSingleSourceForBaselineThreshold() throws Exception {
        DetectionReferenceBaselineConfiguration configuration = officialConfig();
        Path out = tempDir.resolve("single-source");
        DetectionReferenceBaselineCaptureResult captured =
            new DetectionReferenceBaselineCapture().capture(configuration, out);

        assertThat(configuration.classification().anomalyThreshold()).isEqualTo(0.5);
        assertThat(captured.manifest().classification().anomalyThreshold()).isEqualTo(0.5);
        assertThat(readEvidence(out).classification().anomalyThreshold()).isEqualTo(0.5);
        assertThat(captured.manifest().classification().thresholdBoundary())
            .isEqualTo(DetectionReferenceBaselineSchemas.THRESHOLD_BOUNDARY);
    }

    private static void assertBaselineIdentity(DetectionReferenceBaselineCaptureResult result) {
        assertThat(result.baselineId()).isEqualTo(DetectionReferenceBaselineSchemas.BASELINE_ID);
        assertThat(result.baselineSchemaVersion())
            .isEqualTo(DetectionReferenceBaselineSchemas.BASELINE_SCHEMA_VERSION);
        assertThat(result.manifest().baselineKind())
            .isEqualTo(DetectionReferenceBaselineSchemas.BASELINE_KIND);
        assertThat(result.manifest().baselineId()).isEqualTo(DetectionReferenceBaselineSchemas.BASELINE_ID);
    }

    private static void assertThresholdAndProvenance(DetectionReferenceBaselineCaptureResult result) {
        DetectionReferenceBaselineManifest manifest = result.manifest();
        assertThat(manifest.classification().anomalyThreshold()).isEqualTo(0.5);
        assertThat(manifest.reference().datasetId()).isEqualTo(ReferenceDatasetGenerator.DATASET_ID);
        assertThat(manifest.reference().annotationSchemaVersion())
            .isEqualTo(ReferenceDatasetAnnotations.SCHEMA_VERSION);
        assertThat(manifest.annotationsSha256()).matches("[0-9a-f]{64}");
        assertThat(manifest.reference().eventsSha256()).matches("[0-9a-f]{64}");
        assertThat(manifest.replay().resultsSha256()).matches("[0-9a-f]{64}");
        assertThat(manifest.replay().configurationFingerprint()).matches("[0-9a-f]{64}");
        assertThat(manifest.replay().scorerId()).isNotBlank();
        assertThat(manifest.replay().policyId()).isNotBlank();
        assertThat(manifest.structure().referenceEventCount()).isEqualTo(136);
        assertThat(manifest.structure().scenarioCount()).isEqualTo(11);
        assertThat(manifest.structure().alignedObservationCount()).isEqualTo(84);
        assertThat(manifest.artifacts().evaluationJsonSha256()).isEqualTo(result.evaluationJsonSha256());
        assertThat(manifest.artifacts().evaluationMarkdownSha256())
            .isEqualTo(result.evaluationMarkdownSha256());
    }

    private static void assertRequiredLimitations(List<String> limitations) {
        assertThat(limitations).anyMatch(text -> text.toUpperCase().contains("SYNTHETIC"));
        assertThat(limitations).anyMatch(text -> text.toUpperCase().contains("PRODUCTION TRAFFIC"));
        assertThat(limitations).anyMatch(text -> text.toUpperCase().contains("PRODUCTION EFFICACY"));
        assertThat(limitations).anyMatch(text -> text.toUpperCase().contains("PRODUCTION SLA"));
        assertThat(limitations).anyMatch(text -> text.contains("fixed reference"));
        assertThat(limitations).anyMatch(text -> text.toUpperCase().contains("PRODUCTION RECOMMENDATION"));
        assertThat(limitations).anyMatch(text -> text.contains("ANOMALOUS != MALICIOUS"));
        assertThat(limitations).anyMatch(text -> text.contains("DETECTION DELAY != REQUEST LATENCY"));
        assertThat(limitations).anyMatch(text -> text.contains("FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE"));
        assertThat(limitations).anyMatch(text -> text.contains("MONITOR"));
        assertThat(limitations).anyMatch(text -> text.contains("ENFORCE"));
        assertThat(limitations).anyMatch(text -> text.contains("QUALITY GATE"));
        assertThat(limitations).noneMatch(text -> text.toLowerCase().contains("optimal threshold"));
    }

    private static void assertByteIdenticalArtifacts(Path left, Path right) throws Exception {
        assertThat(Files.readAllBytes(left.resolve("manifest.json")))
            .containsExactly(Files.readAllBytes(right.resolve("manifest.json")));
        assertThat(Files.readAllBytes(left.resolve("evaluation.json")))
            .containsExactly(Files.readAllBytes(right.resolve("evaluation.json")));
        assertThat(Files.readAllBytes(left.resolve("evaluation.md")))
            .containsExactly(Files.readAllBytes(right.resolve("evaluation.md")));
        assertThat(TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(left.resolve("manifest.json"))))
            .isEqualTo(TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(right.resolve("manifest.json"))));
    }

    private static void copyBaselineDirectory(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        for (String name : List.of("manifest.json", "evaluation.json", "evaluation.md")) {
            Files.copy(source.resolve(name), target.resolve(name));
        }
    }

    private static void assertPrivacyAndNoPathLeak(Path left, Path right) throws Exception {
        for (Path dir : List.of(left, right)) {
            for (String name : List.of("manifest.json", "evaluation.json", "evaluation.md")) {
                String text = Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
                assertThat(text).doesNotContain(left.toAbsolutePath().normalize().toString());
                assertThat(text).doesNotContain(right.toAbsolutePath().normalize().toString());
                assertThat(text).doesNotContain(System.getProperty("user.home"));
                for (String marker : List.of(
                    "Authorization", "Bearer ", "cookie", "FeatureSnapshot",
                    "requestsPerWindow", "endpointEntropy", "id:synthetic-"
                )) {
                    assertThat(text).doesNotContainIgnoringCase(marker);
                }
            }
        }
    }

    private DetectionReferenceBaselineConfiguration officialConfig() {
        DetectionReferenceBaselineConfiguration official =
            DetectionReferenceBaselineConfiguration.officialReference();
        return new DetectionReferenceBaselineConfiguration(
            official.classification(),
            official.replayConfiguration(),
            official.expectedDatasetId(),
            official.expectedAnnotationSchemaVersion(),
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE)
        );
    }

    private static DetectionEvaluationEvidence readEvidence(Path baselineDirectory) throws Exception {
        // Reconstruct via a fresh evaluation into a sibling temp directory is heavier; reuse capture
        // evidence by re-running evaluation into an isolated folder and comparing hashes instead when needed.
        Path evidenceDir = baselineDirectory.getParent().resolve(baselineDirectory.getFileName() + "-evidence-reload");
        if (Files.exists(evidenceDir)) {
            deleteRecursively(evidenceDir);
        }
        DetectionEvaluationRunner.DetectionEvaluationRun run = new DetectionEvaluationRunner().evaluate(
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE),
            ReplayConfiguration.referenceDefaults(),
            new DetectionClassificationConfiguration(0.5),
            evidenceDir
        );
        assertThat(run.writtenEvidence().jsonSha256()).isEqualTo(
            TrainingFingerprintHashes.sha256HexBytes(
                Files.readAllBytes(baselineDirectory.resolve("evaluation.json"))
            )
        );
        return run.evidence();
    }

    private static DetectionEvaluationMetrics withMetricsThreshold(DetectionEvaluationMetrics metrics, double threshold) {
        return new DetectionEvaluationMetrics(
            metrics.datasetId(),
            metrics.replayRunId(),
            new DetectionClassificationConfiguration(threshold),
            metrics.totalObservationCount(),
            metrics.evaluablePredictionCount(),
            metrics.excludedPredictionCount(),
            metrics.confusionMatrix(),
            metrics.metrics(),
            metrics.scenarios()
        );
    }

    private static TemporalDetectionEvaluation withTemporalThreshold(TemporalDetectionEvaluation temporal,
                                                                     double threshold) {
        return new TemporalDetectionEvaluation(
            temporal.datasetId(),
            temporal.replayRunId(),
            new DetectionClassificationConfiguration(threshold),
            temporal.scenarios()
        );
    }

    private static DetectionReferenceBaselineManifest withDatasetId(DetectionReferenceBaselineManifest manifest,
                                                                    String datasetId) {
        DetectionEvaluationEvidence.ReferenceProvenance reference = manifest.reference();
        return copyManifest(
            manifest,
            new DetectionEvaluationEvidence.ReferenceProvenance(
                datasetId,
                reference.datasetSchemaVersion(),
                reference.evaluationEventSchemaVersion(),
                reference.featureSchemaVersion(),
                reference.annotationSchemaVersion(),
                reference.sourceClassification(),
                reference.transformationVersion(),
                reference.ordering(),
                reference.eventsSha256()
            ),
            manifest.annotationsSha256(),
            new DetectionEvaluationEvidence.ReplayProvenance(
                manifest.replay().replaySchemaVersion(),
                manifest.replay().replayRunId(),
                datasetId,
                manifest.replay().replayMode(),
                manifest.replay().scorerId(),
                manifest.replay().scorerVersion(),
                manifest.replay().policyId(),
                manifest.replay().policyVersion(),
                manifest.replay().configurationFingerprint(),
                manifest.replay().aiSentinelVersion(),
                manifest.replay().resultsSha256()
            ),
            manifest.structure()
        );
    }

    private static DetectionReferenceBaselineManifest withEventsHash(DetectionReferenceBaselineManifest manifest,
                                                                     String eventsSha256) {
        DetectionEvaluationEvidence.ReferenceProvenance reference = manifest.reference();
        return copyManifest(
            manifest,
            new DetectionEvaluationEvidence.ReferenceProvenance(
                reference.datasetId(),
                reference.datasetSchemaVersion(),
                reference.evaluationEventSchemaVersion(),
                reference.featureSchemaVersion(),
                reference.annotationSchemaVersion(),
                reference.sourceClassification(),
                reference.transformationVersion(),
                reference.ordering(),
                eventsSha256
            ),
            manifest.annotationsSha256(),
            manifest.replay(),
            manifest.structure()
        );
    }

    private static DetectionReferenceBaselineManifest withAnnotationSchema(DetectionReferenceBaselineManifest manifest,
                                                                           String schema) {
        DetectionEvaluationEvidence.ReferenceProvenance reference = manifest.reference();
        return copyManifest(
            manifest,
            new DetectionEvaluationEvidence.ReferenceProvenance(
                reference.datasetId(),
                reference.datasetSchemaVersion(),
                reference.evaluationEventSchemaVersion(),
                reference.featureSchemaVersion(),
                schema,
                reference.sourceClassification(),
                reference.transformationVersion(),
                reference.ordering(),
                reference.eventsSha256()
            ),
            manifest.annotationsSha256(),
            manifest.replay(),
            manifest.structure()
        );
    }

    private static DetectionReferenceBaselineManifest withAnnotationsHash(DetectionReferenceBaselineManifest manifest,
                                                                          String hash) {
        return copyManifest(manifest, manifest.reference(), hash, manifest.replay(), manifest.structure());
    }

    private static DetectionReferenceBaselineManifest withReplayRunId(DetectionReferenceBaselineManifest manifest,
                                                                      String replayRunId) {
        DetectionEvaluationEvidence.ReplayProvenance replay = manifest.replay();
        return copyManifest(
            manifest,
            manifest.reference(),
            manifest.annotationsSha256(),
            new DetectionEvaluationEvidence.ReplayProvenance(
                replay.replaySchemaVersion(),
                replayRunId,
                replay.datasetId(),
                replay.replayMode(),
                replay.scorerId(),
                replay.scorerVersion(),
                replay.policyId(),
                replay.policyVersion(),
                replay.configurationFingerprint(),
                replay.aiSentinelVersion(),
                replay.resultsSha256()
            ),
            manifest.structure()
        );
    }

    private static DetectionReferenceBaselineManifest withScorer(DetectionReferenceBaselineManifest manifest,
                                                                 String scorerId,
                                                                 String scorerVersion) {
        DetectionEvaluationEvidence.ReplayProvenance replay = manifest.replay();
        return copyManifest(
            manifest,
            manifest.reference(),
            manifest.annotationsSha256(),
            new DetectionEvaluationEvidence.ReplayProvenance(
                replay.replaySchemaVersion(),
                replay.replayRunId(),
                replay.datasetId(),
                replay.replayMode(),
                scorerId,
                scorerVersion,
                replay.policyId(),
                replay.policyVersion(),
                replay.configurationFingerprint(),
                replay.aiSentinelVersion(),
                replay.resultsSha256()
            ),
            manifest.structure()
        );
    }

    private static DetectionReferenceBaselineManifest withPolicy(DetectionReferenceBaselineManifest manifest,
                                                                 String policyId,
                                                                 String policyVersion) {
        DetectionEvaluationEvidence.ReplayProvenance replay = manifest.replay();
        return copyManifest(
            manifest,
            manifest.reference(),
            manifest.annotationsSha256(),
            new DetectionEvaluationEvidence.ReplayProvenance(
                replay.replaySchemaVersion(),
                replay.replayRunId(),
                replay.datasetId(),
                replay.replayMode(),
                replay.scorerId(),
                replay.scorerVersion(),
                policyId,
                policyVersion,
                replay.configurationFingerprint(),
                replay.aiSentinelVersion(),
                replay.resultsSha256()
            ),
            manifest.structure()
        );
    }

    private static DetectionReferenceBaselineManifest withFingerprint(DetectionReferenceBaselineManifest manifest,
                                                                      String fingerprint) {
        DetectionEvaluationEvidence.ReplayProvenance replay = manifest.replay();
        return copyManifest(
            manifest,
            manifest.reference(),
            manifest.annotationsSha256(),
            new DetectionEvaluationEvidence.ReplayProvenance(
                replay.replaySchemaVersion(),
                replay.replayRunId(),
                replay.datasetId(),
                replay.replayMode(),
                replay.scorerId(),
                replay.scorerVersion(),
                replay.policyId(),
                replay.policyVersion(),
                fingerprint,
                replay.aiSentinelVersion(),
                replay.resultsSha256()
            ),
            manifest.structure()
        );
    }

    private static DetectionReferenceBaselineManifest withResultsHash(DetectionReferenceBaselineManifest manifest,
                                                                      String resultsSha256) {
        DetectionEvaluationEvidence.ReplayProvenance replay = manifest.replay();
        return copyManifest(
            manifest,
            manifest.reference(),
            manifest.annotationsSha256(),
            new DetectionEvaluationEvidence.ReplayProvenance(
                replay.replaySchemaVersion(),
                replay.replayRunId(),
                replay.datasetId(),
                replay.replayMode(),
                replay.scorerId(),
                replay.scorerVersion(),
                replay.policyId(),
                replay.policyVersion(),
                replay.configurationFingerprint(),
                replay.aiSentinelVersion(),
                resultsSha256
            ),
            manifest.structure()
        );
    }

    private static DetectionReferenceBaselineManifest withStructure(DetectionReferenceBaselineManifest manifest,
                                                                    DetectionEvaluationEvidence.StructuralCounts structure) {
        return copyManifest(
            manifest,
            manifest.reference(),
            manifest.annotationsSha256(),
            manifest.replay(),
            structure
        );
    }

    private static DetectionReferenceBaselineManifest copyManifest(
        DetectionReferenceBaselineManifest manifest,
        DetectionEvaluationEvidence.ReferenceProvenance reference,
        String annotationsSha256,
        DetectionEvaluationEvidence.ReplayProvenance replay,
        DetectionEvaluationEvidence.StructuralCounts structure
    ) {
        return new DetectionReferenceBaselineManifest(
            manifest.baselineSchemaVersion(),
            manifest.baselineId(),
            manifest.baselineKind(),
            manifest.purpose(),
            reference,
            annotationsSha256,
            replay,
            manifest.classification(),
            structure,
            manifest.artifacts(),
            manifest.limitations()
        );
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

    private static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            for (int i = paths.size() - 1; i >= 0; i--) {
                Files.deleteIfExists(paths.get(i));
            }
        }
    }
}
