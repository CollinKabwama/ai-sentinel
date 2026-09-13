package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayPolicyConfiguration;
import dev.aisentinel.core.replay.ReplayScorerConfiguration;
import dev.aisentinel.core.replay.ReplayScorerKind;
import dev.aisentinel.core.replay.ReplaySchemas;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verification and drift-detection coverage for the Official Detection Reference Baseline.
 * Assertions are structural. Drift means difference, not defect or quality gate failure.
 */
class DetectionReferenceBaselineVerificationTest {

    @TempDir
    Path tempDir;

    @Test
    void unchangedRepositoryTruthMatchesOfficialBaselineExactly() throws Exception {
        DetectionReferenceBaselineVerificationResult result =
            new DetectionReferenceBaselineVerifier().verifyOfficial(officialBaseline());

        assertThat(result.status()).isEqualTo(DetectionReferenceBaselineVerificationStatus.MATCH);
        assertThat(result.totalDriftEntries()).isZero();
        assertThat(result.evaluationJsonBytesEqual()).isTrue();
        assertThat(result.evaluationMarkdownBytesEqual()).isTrue();
        assertThat(result.currentEvaluationJsonSha256()).isEqualTo(result.baselineEvaluationJsonSha256());
        assertThat(result.currentEvaluationMarkdownSha256()).isEqualTo(result.baselineEvaluationMarkdownSha256());
        assertThat(result.baselineEvaluationJsonSha256())
            .isEqualTo("489bc146bd1706bb9c9f39d727cc400340bc7cff1be4e9b05f563b23638d8c6e");
        assertThat(result.baselineEvaluationMarkdownSha256())
            .isEqualTo("49266beda02bb1bf6f032e2378ca23d041d83a57620c8328a1a3fff74117587f");
    }

    @Test
    void datasetMutationReportsDatasetProvenanceDrift() throws Exception {
        Path datasetCopy = copyTrackedDataset("dataset-mut");
        Path events = datasetCopy.resolve("events.jsonl");
        String original = Files.readString(events, StandardCharsets.UTF_8);
        String mutated = original.replaceFirst("\"payloadSizeBytes\":128", "\"payloadSizeBytes\":129");
        assertThat(mutated).isNotEqualTo(original);
        Files.writeString(events, mutated, StandardCharsets.UTF_8);
        rewriteDatasetEventsChecksum(datasetCopy);

        DetectionReferenceBaselineVerificationResult result = verifyAgainst(
            datasetCopy,
            datasetCopy.resolve("annotations.json"),
            ReplayConfiguration.referenceDefaults(),
            new DetectionClassificationConfiguration(0.5)
        );

        assertThat(result.status()).isEqualTo(DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED);
        assertThat(result.driftEntries())
            .anyMatch(entry -> entry.category() == DetectionReferenceBaselineDriftCategory.DATASET_PROVENANCE
                && entry.field().equals("eventsSha256"));
    }

    @Test
    void annotationMutationReportsAnnotationProvenanceDrift() throws Exception {
        Path datasetCopy = copyTrackedDataset("ann-mut");
        Path annotations = datasetCopy.resolve("annotations.json");
        String original = Files.readString(annotations, StandardCharsets.UTF_8);
        String mutated = original.replaceFirst(
            "\"expectedClass\":\"NORMAL\",\"anomalyExpected\":false",
            "\"expectedClass\":\"SYNTHETIC_ANOMALOUS\",\"anomalyExpected\":true"
        );
        assertThat(mutated).isNotEqualTo(original);
        Files.writeString(annotations, mutated, StandardCharsets.UTF_8);

        DetectionReferenceBaselineVerificationResult result = verifyAgainst(
            datasetCopy,
            annotations,
            ReplayConfiguration.referenceDefaults(),
            new DetectionClassificationConfiguration(0.5)
        );

        assertThat(result.status()).isEqualTo(DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED);
        assertThat(result.driftEntries())
            .anyMatch(entry -> entry.category() == DetectionReferenceBaselineDriftCategory.ANNOTATION_PROVENANCE
                && entry.field().equals("annotationsSha256"));
    }

    @Test
    void thresholdConfigurationDriftIsReported() {
        DetectionReferenceBaselineVerificationResult result = verifyAgainst(
            locate(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            locate(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE),
            ReplayConfiguration.referenceDefaults(),
            new DetectionClassificationConfiguration(0.75)
        );

        assertThat(result.status()).isEqualTo(DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED);
        assertThat(result.driftEntries())
            .anyMatch(entry -> entry.category() == DetectionReferenceBaselineDriftCategory.CLASSIFICATION_CONFIGURATION
                && entry.field().equals("anomalyThreshold")
                && entry.baselineValue().equals("0.5")
                && entry.currentValue().equals("0.75"));
    }

    @Test
    void scorerProvenanceDriftIsReported() {
        ReplayScorerConfiguration defaults = ReplayScorerConfiguration.statisticalDefaults();
        ReplayConfiguration altered = new ReplayConfiguration(
            ReplaySchemas.REPLAY_MODE_FRESH_RUN,
            "0.3.0",
            new ReplayScorerConfiguration(
                ReplayScorerKind.STATISTICAL,
                "statistical-other",
                "9.9.9",
                defaults.maxKeys(),
                defaults.ttlMs(),
                defaults.warmupMinSamples(),
                defaults.warmupScore()
            ),
            ReplayPolicyConfiguration.defaultThresholds()
        );

        DetectionReferenceBaselineVerificationResult result = verifyAgainst(
            locate(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            locate(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE),
            altered,
            new DetectionClassificationConfiguration(0.5)
        );

        assertThat(result.status()).isEqualTo(DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED);
        assertThat(result.driftEntries())
            .anyMatch(entry -> entry.category() == DetectionReferenceBaselineDriftCategory.SCORER_PROVENANCE
                && entry.field().equals("scorerId"));
        assertThat(result.driftEntries())
            .anyMatch(entry -> entry.category() == DetectionReferenceBaselineDriftCategory.SCORER_PROVENANCE
                && entry.field().equals("scorerVersion"));
    }

    @Test
    void policyProvenanceDriftIsReported() {
        ReplayPolicyConfiguration defaults = ReplayPolicyConfiguration.defaultThresholds();
        ReplayConfiguration altered = new ReplayConfiguration(
            ReplaySchemas.REPLAY_MODE_FRESH_RUN,
            "0.3.0",
            ReplayScorerConfiguration.statisticalDefaults(),
            new ReplayPolicyConfiguration(
                "threshold-policy-other",
                "9.9.9",
                defaults.evaluationMode(),
                defaults.moderateThreshold(),
                defaults.elevatedThreshold(),
                defaults.highThreshold(),
                defaults.criticalThreshold()
            )
        );

        DetectionReferenceBaselineVerificationResult result = verifyAgainst(
            locate(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            locate(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE),
            altered,
            new DetectionClassificationConfiguration(0.5)
        );

        assertThat(result.status()).isEqualTo(DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED);
        assertThat(result.driftEntries())
            .anyMatch(entry -> entry.category() == DetectionReferenceBaselineDriftCategory.POLICY_PROVENANCE
                && entry.field().equals("policyId"));
        assertThat(result.driftEntries())
            .anyMatch(entry -> entry.category() == DetectionReferenceBaselineDriftCategory.POLICY_PROVENANCE
                && entry.field().equals("policyVersion"));
    }

    @Test
    void structuralMetricAndTemporalDriftAppearWhenAnnotationsChangeTruth() throws Exception {
        Path datasetCopy = copyTrackedDataset("truth-metrics");
        Path annotations = datasetCopy.resolve("annotations.json");
        String original = Files.readString(annotations, StandardCharsets.UTF_8);
        String mutated = original.replaceFirst(
            "\"expectedClass\":\"SYNTHETIC_ANOMALOUS\",\"anomalyExpected\":true",
            "\"expectedClass\":\"NORMAL\",\"anomalyExpected\":false"
        );
        assertThat(mutated).isNotEqualTo(original);
        Files.writeString(annotations, mutated, StandardCharsets.UTF_8);

        DetectionReferenceBaselineVerificationResult result = verifyAgainst(
            datasetCopy,
            annotations,
            ReplayConfiguration.referenceDefaults(),
            new DetectionClassificationConfiguration(0.5)
        );

        assertThat(result.status()).isEqualTo(DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED);
        assertThat(result.driftEntries())
            .anyMatch(entry -> entry.category() == DetectionReferenceBaselineDriftCategory.STRUCTURAL_EVIDENCE);
        assertThat(result.driftEntries())
            .anyMatch(entry -> entry.category() == DetectionReferenceBaselineDriftCategory.DETECTION_METRICS);
        assertThat(result.driftEntries())
            .anyMatch(entry -> entry.category() == DetectionReferenceBaselineDriftCategory.TEMPORAL_EVIDENCE);
    }

    @Test
    void tamperedEvaluationArtifactsCauseBaselineIntegrityFailure() throws Exception {
        Path baseline = copyOfficialBaseline("tamper-json");
        Files.writeString(
            baseline.resolve("evaluation.json"),
            Files.readString(baseline.resolve("evaluation.json"), StandardCharsets.UTF_8) + " ",
            StandardCharsets.UTF_8
        );
        DetectionReferenceBaselineVerificationResult jsonResult =
            new DetectionReferenceBaselineVerifier().verifyOfficial(baseline);
        assertThat(jsonResult.status())
            .isEqualTo(DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE);

        Path baselineMd = copyOfficialBaseline("tamper-md");
        Files.writeString(
            baselineMd.resolve("evaluation.md"),
            Files.readString(baselineMd.resolve("evaluation.md"), StandardCharsets.UTF_8) + "x",
            StandardCharsets.UTF_8
        );
        DetectionReferenceBaselineVerificationResult mdResult =
            new DetectionReferenceBaselineVerifier().verifyOfficial(baselineMd);
        assertThat(mdResult.status())
            .isEqualTo(DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE);
    }

    @Test
    void tamperedAndMalformedManifestsCauseBaselineIntegrityFailure() throws Exception {
        Path thresholdTamper = copyOfficialBaseline("manifest-threshold");
        rewriteManifestField(thresholdTamper, "\"anomalyThreshold\":0.5", "\"anomalyThreshold\":0.75");
        assertThat(new DetectionReferenceBaselineVerifier().verifyOfficial(thresholdTamper).status())
            .isEqualTo(DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE);

        Path hashTamper = copyOfficialBaseline("manifest-hash");
        rewriteManifestField(
            hashTamper,
            "\"evaluationJsonSha256\":\"489bc146bd1706bb9c9f39d727cc400340bc7cff1be4e9b05f563b23638d8c6e\"",
            "\"evaluationJsonSha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\""
        );
        assertThat(new DetectionReferenceBaselineVerifier().verifyOfficial(hashTamper).status())
            .isEqualTo(DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE);

        Path malformed = copyOfficialBaseline("manifest-malformed");
        Files.writeString(malformed.resolve("manifest.json"), "{not-json", StandardCharsets.UTF_8);
        assertThat(new DetectionReferenceBaselineVerifier().verifyOfficial(malformed).status())
            .isEqualTo(DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE);

        Path unsupportedSchema = copyOfficialBaseline("manifest-schema");
        rewriteManifestField(
            unsupportedSchema,
            "\"baselineSchemaVersion\":\"1\"",
            "\"baselineSchemaVersion\":\"999\""
        );
        assertThat(new DetectionReferenceBaselineVerifier().verifyOfficial(unsupportedSchema).status())
            .isEqualTo(DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE);
    }

    @Test
    void malformedNumericManifestValuesCauseBaselineIntegrityFailure() throws Exception {
        Path fractionalCount = copyOfficialBaseline("manifest-fractional-count");
        rewriteManifestField(fractionalCount, "\"referenceEventCount\":136", "\"referenceEventCount\":136.5");
        assertThat(new DetectionReferenceBaselineVerifier().verifyOfficial(fractionalCount).status())
            .isEqualTo(DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE);

        Path nonFiniteThreshold = copyOfficialBaseline("manifest-nonfinite-threshold");
        rewriteManifestField(nonFiniteThreshold, "\"anomalyThreshold\":0.5", "\"anomalyThreshold\":1e309");
        assertThat(new DetectionReferenceBaselineVerifier().verifyOfficial(nonFiniteThreshold).status())
            .isEqualTo(DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE);
    }

    @Test
    void currentEvaluationFailureIsDistinctFromDrift() {
        DetectionReferenceBaselineVerificationResult result = verifyAgainst(
            locate(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            tempDir.resolve("missing-annotations.json"),
            ReplayConfiguration.referenceDefaults(),
            new DetectionClassificationConfiguration(0.5)
        );

        assertThat(result.status())
            .isEqualTo(DetectionReferenceBaselineVerificationStatus.CURRENT_EVALUATION_FAILURE);
        assertThat(result.driftEntries()).isEmpty();
        assertThat(result.detail()).contains("current evaluation failed");
    }

    @Test
    void verificationReportsAreDeterministicPathIndependentAndPrivate() throws Exception {
        Path outA = tempDir.resolve("report-a");
        Path outB = tempDir.resolve("nested").resolve("report-b");
        DetectionReferenceBaselineVerifier verifier = new DetectionReferenceBaselineVerifier();
        DetectionReferenceBaselineVerificationResult first = verifier.verifyOfficial(officialBaseline());
        DetectionReferenceBaselineVerificationResult second = verifier.verifyOfficial(officialBaseline());
        assertThat(first).isEqualTo(second);

        DetectionReferenceBaselineVerifier.WrittenVerificationReports writtenA =
            verifier.writeReports(first, outA);
        DetectionReferenceBaselineVerifier.WrittenVerificationReports writtenB =
            verifier.writeReports(second, outB);

        assertThat(Files.readAllBytes(outA.resolve("verification.json")))
            .containsExactly(Files.readAllBytes(outB.resolve("verification.json")));
        assertThat(Files.readAllBytes(outA.resolve("verification.md")))
            .containsExactly(Files.readAllBytes(outB.resolve("verification.md")));
        assertThat(writtenA.jsonSha256()).isEqualTo(writtenB.jsonSha256());
        assertThat(writtenA.markdownSha256()).isEqualTo(writtenB.markdownSha256());

        String json = Files.readString(outA.resolve("verification.json"), StandardCharsets.UTF_8);
        String markdown = Files.readString(outA.resolve("verification.md"), StandardCharsets.UTF_8);
        for (String text : List.of(json, markdown)) {
            assertThat(text).doesNotContain(outA.toAbsolutePath().normalize().toString());
            assertThat(text).doesNotContain(outB.toAbsolutePath().normalize().toString());
            assertThat(text).doesNotContain(System.getProperty("user.home"));
            for (String marker : List.of(
                "Authorization", "Bearer ", "cookie", "FeatureSnapshot",
                "requestsPerWindow", "endpointEntropy", "id:synthetic-"
            )) {
                assertThat(text).doesNotContainIgnoringCase(marker);
            }
        }

        assertThatThrownBy(() -> verifier.writeReports(first, outA))
            .isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void driftOrderingIsDeterministic() {
        DetectionReferenceBaselineVerificationResult first = verifyAgainst(
            locate(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            locate(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE),
            new ReplayConfiguration(
                ReplaySchemas.REPLAY_MODE_FRESH_RUN,
                "0.3.0",
                new ReplayScorerConfiguration(
                    ReplayScorerKind.STATISTICAL,
                    "statistical-other",
                    "9.9.9",
                    ReplayScorerConfiguration.statisticalDefaults().maxKeys(),
                    ReplayScorerConfiguration.statisticalDefaults().ttlMs(),
                    ReplayScorerConfiguration.statisticalDefaults().warmupMinSamples(),
                    ReplayScorerConfiguration.statisticalDefaults().warmupScore()
                ),
                new ReplayPolicyConfiguration(
                    "threshold-policy-other",
                    "9.9.9",
                    ReplayPolicyConfiguration.defaultThresholds().evaluationMode(),
                    0.2,
                    0.4,
                    0.6,
                    0.8
                )
            ),
            new DetectionClassificationConfiguration(0.75)
        );
        DetectionReferenceBaselineVerificationResult second = verifyAgainst(
            locate(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY),
            locate(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE),
            new ReplayConfiguration(
                ReplaySchemas.REPLAY_MODE_FRESH_RUN,
                "0.3.0",
                new ReplayScorerConfiguration(
                    ReplayScorerKind.STATISTICAL,
                    "statistical-other",
                    "9.9.9",
                    ReplayScorerConfiguration.statisticalDefaults().maxKeys(),
                    ReplayScorerConfiguration.statisticalDefaults().ttlMs(),
                    ReplayScorerConfiguration.statisticalDefaults().warmupMinSamples(),
                    ReplayScorerConfiguration.statisticalDefaults().warmupScore()
                ),
                new ReplayPolicyConfiguration(
                    "threshold-policy-other",
                    "9.9.9",
                    ReplayPolicyConfiguration.defaultThresholds().evaluationMode(),
                    0.2,
                    0.4,
                    0.6,
                    0.8
                )
            ),
            new DetectionClassificationConfiguration(0.75)
        );

        assertThat(first.driftEntries()).isEqualTo(second.driftEntries());
        assertThat(first.driftEntries()).isSortedAccordingTo(
            Comparator.comparing(DetectionReferenceBaselineDriftEntry::category)
                .thenComparing(DetectionReferenceBaselineDriftEntry::field)
                .thenComparing(DetectionReferenceBaselineDriftEntry::baselineValue)
                .thenComparing(DetectionReferenceBaselineDriftEntry::currentValue)
        );
    }

    @Test
    void cliExitCodesAndOfficialThresholdSource() {
        assertThat(ReferenceDetectionBaselineVerifyMain.exitCode(
            DetectionReferenceBaselineVerificationStatus.MATCH)).isZero();
        assertThat(ReferenceDetectionBaselineVerifyMain.exitCode(
            DetectionReferenceBaselineVerificationStatus.DRIFT_DETECTED)).isEqualTo(1);
        assertThat(ReferenceDetectionBaselineVerifyMain.exitCode(
            DetectionReferenceBaselineVerificationStatus.BASELINE_INTEGRITY_FAILURE)).isEqualTo(2);
        assertThat(ReferenceDetectionBaselineVerifyMain.exitCode(
            DetectionReferenceBaselineVerificationStatus.CURRENT_EVALUATION_FAILURE)).isEqualTo(3);
        assertThat(DetectionReferenceBaselineConfiguration.officialReference().classification().anomalyThreshold())
            .isEqualTo(0.5);
    }

    private DetectionReferenceBaselineVerificationResult verifyAgainst(Path datasetDirectory,
                                                                       Path annotationsFile,
                                                                       ReplayConfiguration replay,
                                                                       DetectionClassificationConfiguration classification) {
        return new DetectionReferenceBaselineVerifier().verify(
            officialBaseline(),
            datasetDirectory,
            annotationsFile,
            replay,
            classification
        );
    }

    private Path officialBaseline() {
        return locate(DetectionReferenceBaselineVerificationSchemas.TRACKED_BASELINE_DIRECTORY);
    }

    private Path copyOfficialBaseline(String name) throws Exception {
        Path source = officialBaseline();
        Path target = tempDir.resolve(name);
        Files.createDirectories(target);
        for (String file : List.of("manifest.json", "evaluation.json", "evaluation.md")) {
            Files.copy(source.resolve(file), target.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private Path copyTrackedDataset(String name) throws Exception {
        Path source = locate(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY);
        Path target = tempDir.resolve(name);
        Files.createDirectories(target);
        try (var walk = Files.walk(source)) {
            walk.forEach(path -> {
                try {
                    Path relative = source.relativize(path);
                    Path destination = target.resolve(relative.toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        return target;
    }

    private static void rewriteDatasetEventsChecksum(Path datasetDirectory) throws Exception {
        Path events = datasetDirectory.resolve("events.jsonl");
        Path manifest = datasetDirectory.resolve("manifest.json");
        String checksum = TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(events));
        String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);
        String rewritten = manifestText.replaceFirst(
            "\"eventsSha256\"\\s*:\\s*\"[0-9a-f]{64}\"",
            "\"eventsSha256\":\"" + checksum + "\""
        );
        assertThat(rewritten).isNotEqualTo(manifestText);
        Files.writeString(manifest, rewritten, StandardCharsets.UTF_8);
    }

    private static void rewriteManifestField(Path baseline, String from, String to) throws Exception {
        Path manifest = baseline.resolve("manifest.json");
        String text = Files.readString(manifest, StandardCharsets.UTF_8);
        String rewritten = text.replace(from, to);
        assertThat(rewritten).isNotEqualTo(text);
        Files.writeString(manifest, rewritten, StandardCharsets.UTF_8);
    }

    private static Path locate(Path relativePath) {
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
}
