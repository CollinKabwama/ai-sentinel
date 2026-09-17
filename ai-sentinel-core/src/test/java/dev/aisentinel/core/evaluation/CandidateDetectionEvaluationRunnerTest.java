package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.model.FeatureProjectionId;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayEngine;
import dev.aisentinel.core.replay.ReplayException;
import dev.aisentinel.core.replay.ReplayPolicyConfiguration;
import dev.aisentinel.core.replay.ReplaySchemas;
import dev.aisentinel.core.replay.ReplayScorerConfiguration;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.IsolationForestModel;
import dev.aisentinel.core.scoring.IsolationForestModelCodec;
import dev.aisentinel.core.scoring.IsolationForestTrainer;
import dev.aisentinel.core.scoring.artifact.ArtifactDigest;
import dev.aisentinel.core.scoring.artifact.CandidateScorerLoadFailureCode;
import dev.aisentinel.core.scoring.artifact.LoadedCandidateScorer;
import dev.aisentinel.core.scoring.artifact.ScorerArtifactCapabilities;
import dev.aisentinel.core.scoring.artifact.ScorerArtifactDescriptor;
import dev.aisentinel.core.scoring.artifact.ScorerOutputRange;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateDetectionEvaluationRunnerTest {

    private static final DetectionClassificationConfiguration THRESHOLD =
        new DetectionClassificationConfiguration(DetectionReferenceBaselineSchemas.REFERENCE_CLASSIFICATION_THRESHOLD);

    @TempDir
    Path tempDir;

    @Test
    void readyIsolationForestCandidateEvaluatesThroughExistingFramework() throws Exception {
        byte[] payload = encodeIsolationForest(42L);
        ScorerArtifactDescriptor descriptor = ifDescriptor("research-if-candidate", "1.0.0", "artifact-1", payload);
        Path officialBaseline = officialBaselineDirectory();
        String baselineBefore = hashDirectory(officialBaseline);

        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult result =
            new CandidateDetectionEvaluationRunner().evaluate(
                descriptor,
                payload,
                trackedDatasetDirectory(),
                trackedAnnotationsFile(),
                THRESHOLD,
                tempDir.resolve("ready-if")
            );

        assertThat(result.status()).isEqualTo(CandidateDetectionEvaluationStatus.COMPLETED);
        assertThat(result.evidence().evaluation()).isPresent();
        assertThat(result.alignment()).isPresent();
        assertThat(result.metrics()).isPresent();
        assertThat(result.temporal()).isPresent();
        assertThat(result.alignment().orElseThrow().observations())
            .allSatisfy(observation -> assertThat(observation.prediction().source())
                .isEqualTo(EvaluationPredictionSource.CANDIDATE_REPLAY_SCORE));
        assertThat(result.metrics().orElseThrow()).isEqualTo(result.evidence().evaluation().orElseThrow().metrics());
        assertThat(result.temporal().orElseThrow()).isEqualTo(result.evidence().evaluation().orElseThrow().temporal());
        assertThat(result.evidence().candidate().scorerId()).isEqualTo("research-if-candidate");
        assertThat(result.evidence().candidate().scorerVersion()).isEqualTo("1.0.0");
        assertThat(result.evidence().candidate().artifactDigestHex())
            .isEqualTo(descriptor.artifactDigest().digestHex());
        assertThat(result.evidence().candidate().verifiedDigestHex())
            .isEqualTo(descriptor.artifactDigest().digestHex());
        assertThat(result.evidence().candidate().configurationFingerprintSha256Hex())
            .isEqualTo(descriptor.configurationFingerprintSha256Hex());
        assertThat(result.evidence().candidate().configurationFingerprintSha256Hex())
            .isNotEqualTo(result.evidence().candidate().verifiedDigestHex());
        assertThat(result.evidence().evaluation().orElseThrow().reference().datasetId())
            .isEqualTo(ReferenceDatasetGenerator.DATASET_ID);
        assertThat(result.evidence().classification().anomalyThreshold()).isEqualTo(0.5);
        assertThat(result.evidence().limitations()).contains(
            "EVALUATED CANDIDATE != APPROVED CANDIDATE",
            "FRAMEWORK ACCEPTANCE != DETECTION QUALITY ACCEPTANCE"
        );
        assertThat(Files.exists(tempDir.resolve("ready-if").resolve("candidate-evaluation.json"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("ready-if").resolve("candidate-evaluation.md"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("ready-if").resolve("evaluation.json"))).isFalse();
        assertThat(hashDirectory(officialBaseline)).isEqualTo(baselineBefore);
        assertThat(result.evidence().evaluation().orElseThrow().replay().scorerVersion())
            .contains(descriptor.artifactDigest().digestHex());
        assertThat(result.evidence().evaluation().orElseThrow().replay().scorerId())
            .isEqualTo("research-if-candidate");
    }

    @Test
    void repeatedReadyEvaluationsAreDeterministicAndIsolated() throws Exception {
        byte[] payload = encodeIsolationForest(42L);
        ScorerArtifactDescriptor descriptor = ifDescriptor("research-if-candidate", "1.0.0", "artifact-1", payload);

        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult first =
            evaluateCandidate(descriptor, payload, "repeat-a");
        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult second =
            evaluateCandidate(descriptor, payload, "repeat-b");

        assertThat(first.evidence()).isEqualTo(second.evidence());
        assertThat(first.writtenEvidence().jsonSha256()).isEqualTo(second.writtenEvidence().jsonSha256());
        assertThat(first.writtenEvidence().markdownSha256()).isEqualTo(second.writtenEvidence().markdownSha256());
        assertThat(Files.readAllBytes(tempDir.resolve("repeat-a").resolve("candidate-evaluation.json")))
            .containsExactly(Files.readAllBytes(tempDir.resolve("repeat-b").resolve("candidate-evaluation.json")));
        assertThat(predictionScores(first)).containsExactlyElementsOf(predictionScores(second));
    }

    @Test
    void annotationTruthDoesNotChangeCandidatePredictions() throws Exception {
        byte[] payload = encodeIsolationForest(7L);
        ScorerArtifactDescriptor descriptor = ifDescriptor("leak-check", "1.0.0", "artifact-leak", payload);
        Path flipped = tempDir.resolve("flipped-annotations.json");
        String original = Files.readString(trackedAnnotationsFile(), StandardCharsets.UTF_8);
        String mutated = original
            .replace("\"expectedClass\":\"NORMAL\"", "\"expectedClass\":\"SYNTHETIC_ANOMALOUS\"")
            .replace("\"anomalyExpected\":false", "\"anomalyExpected\":true");
        Files.writeString(flipped, mutated, StandardCharsets.UTF_8);

        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult baseline =
            evaluateCandidate(descriptor, payload, trackedAnnotationsFile(), "truth-original");
        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult flippedRun =
            evaluateCandidate(descriptor, payload, flipped, "truth-flipped");

        assertThat(predictionScores(baseline)).containsExactlyElementsOf(predictionScores(flippedRun));
        assertThat(flippedRun.metrics().orElseThrow()).isNotEqualTo(baseline.metrics().orElseThrow());
        assertThat(Files.readString(trackedAnnotationsFile(), StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    void differentArtifactsChangeProvenanceAndDoNotSpoofEachOther() throws Exception {
        byte[] firstPayload = encodeIsolationForest(1L);
        byte[] secondPayload = encodeIsolationForest(99L);
        ScorerArtifactDescriptor firstDescriptor = ifDescriptor("research-if-candidate", "1.0.0", "artifact-a", firstPayload);
        ScorerArtifactDescriptor secondDescriptor = ifDescriptor("research-if-candidate", "1.0.0", "artifact-b", secondPayload);

        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult first =
            evaluateCandidate(firstDescriptor, firstPayload, "prov-a");
        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult second =
            evaluateCandidate(secondDescriptor, secondPayload, "prov-b");

        assertThat(first.evidence().candidate().verifiedDigestHex())
            .isNotEqualTo(second.evidence().candidate().verifiedDigestHex());
        assertThat(first.evidence().candidate().artifactId()).isEqualTo("artifact-a");
        assertThat(second.evidence().candidate().artifactId()).isEqualTo("artifact-b");
        assertThat(first.evidence().candidate().verifiedDigestHex())
            .isEqualTo(firstDescriptor.artifactDigest().digestHex());
        assertThat(second.evidence().candidate().verifiedDigestHex())
            .isEqualTo(secondDescriptor.artifactDigest().digestHex());
        assertThat(first.evidence().evaluation().orElseThrow().replay().configurationFingerprint())
            .isNotEqualTo(second.evidence().evaluation().orElseThrow().replay().configurationFingerprint());
    }

    @Test
    void completedEvidenceRejectsMismatchedCandidateAndReplayProvenance() throws Exception {
        byte[] payload = encodeIsolationForest(2L);
        ScorerArtifactDescriptor descriptor = ifDescriptor("research-if-candidate", "1.0.0", "artifact-a", payload);
        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult result =
            evaluateCandidate(descriptor, payload, "mismatch-source");
        CandidateEvaluationProvenance original = result.evidence().candidate();
        CandidateEvaluationProvenance spoofed = new CandidateEvaluationProvenance(
            "different-candidate",
            original.scorerVersion(),
            original.artifactId(),
            original.artifactFormat(),
            original.scorerType(),
            original.artifactDigestAlgorithm(),
            original.artifactDigestHex(),
            original.artifactBytesVerified(),
            original.verifiedDigestHex(),
            original.featureSchemaVersion(),
            original.requiredProjection(),
            original.requiredFeatureNames(),
            original.declaredFeatureDimension(),
            original.configurationFingerprintSha256Hex(),
            original.runtimeImplementationId()
        );

        assertThatThrownBy(() -> new CandidateDetectionEvaluationEvidence(
            result.evidence().evidenceSchemaVersion(),
            result.evidence().reportKind(),
            CandidateDetectionEvaluationStatus.COMPLETED,
            spoofed,
            result.evidence().classification(),
            List.of(),
            result.evidence().evaluation().orElseThrow(),
            result.evidence().limitations()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("candidate scorerId must match nested replay evidence");
    }

    @Test
    void loadFailuresDoNotFabricatePredictions() throws Exception {
        byte[] payload = encodeIsolationForest(3L);
        Path officialBaseline = officialBaselineDirectory();
        String baselineBefore = hashDirectory(officialBaseline);

        assertNotReady(
            ifDescriptor("research-if-candidate", "1.0.0", "artifact-1", new byte[] {1, 2, 3}),
            payload,
            "fail-digest",
            CandidateScorerLoadFailureCode.DIGEST_MISMATCH
        );
        assertNotReady(
            ifDescriptor("research-if-candidate", "1.0.0", "artifact-1", payload, "binary"),
            payload,
            "fail-format",
            CandidateScorerLoadFailureCode.UNSUPPORTED_ARTIFACT_FORMAT
        );
        byte[] garbage = new byte[] {'N', 'O', 'P', 'E', 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        assertNotReady(
            ifDescriptor("research-if-candidate", "1.0.0", "artifact-1", garbage),
            garbage,
            "fail-decode",
            CandidateScorerLoadFailureCode.ARTIFACT_DECODE_FAILED
        );
        assertNotReady(
            ifDescriptor("research-if-candidate", "1.0.0", "artifact-1", payload),
            null,
            "fail-unavailable",
            CandidateScorerLoadFailureCode.ARTIFACT_UNAVAILABLE
        );
        ScorerArtifactDescriptor invalidDescriptor = ScorerArtifactDescriptor.builder()
            .scorerId("bad-if")
            .scorerVersion("1.0.0")
            .artifactId("art-1")
            .artifactFormat("binary")
            .scorerType(ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1)
            .artifactDigest(ArtifactDigest.sha256Hex(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            ))
            .featureSchemaVersion(FeatureSchema.VERSION_ID)
            .requiredFeatureNames(FeatureSchema.STATISTICAL_FEATURE_NAMES)
            .declaredFeatureDimension(FeatureSchema.STATISTICAL_DIMENSION)
            .outputRange(ScorerOutputRange.unitInterval())
            .capabilities(new ScorerArtifactCapabilities(false, false, true))
            .build();
        assertNotReady(invalidDescriptor, new byte[] {1}, "fail-descriptor", CandidateScorerLoadFailureCode.DESCRIPTOR_REJECTED);

        assertThat(hashDirectory(officialBaseline)).isEqualTo(baselineBefore);
    }

    @Test
    void invalidScoresAreExcludedAndNotMaximumRisk() throws Exception {
        DetectionEvaluationRunner.DetectionEvaluationRun run =
            new DetectionEvaluationRunner(ReplayEngine.withEvaluationScorer(fixedScore(Double.NaN)))
                .evaluate(
                    trackedDatasetDirectory(),
                    trackedAnnotationsFile(),
                    candidateReplayConfiguration("invalid-score", "test", "aa".repeat(32)),
                    THRESHOLD,
                    tempDir.resolve("invalid-score")
                );

        assertThat(run.alignment().observations())
            .allSatisfy(observation -> {
                assertThat(observation.prediction().anomalyScore()).isNull();
                assertThat(observation.prediction().hasValidDetectorScore()).isFalse();
                assertThat(observation.prediction().evaluationStatuses()).contains(EvaluationStatus.INVALID_SCORE);
            });
        assertThat(run.metrics().evaluablePredictionCount()).isZero();
        assertThat(run.metrics().excludedPredictionCount()).isEqualTo(run.metrics().totalObservationCount());
        assertThat(run.metrics().confusionMatrix().truePositives()
            + run.metrics().confusionMatrix().trueNegatives()
            + run.metrics().confusionMatrix().falsePositives()
            + run.metrics().confusionMatrix().falseNegatives()).isZero();
    }

    @Test
    void negativeAndInfiniteScoresAreNotCoercedToMaximumRisk() throws Exception {
        List<AnomalyScorer> scorers = List.of(
            fixedScore(Double.POSITIVE_INFINITY),
            fixedScore(Double.NEGATIVE_INFINITY),
            fixedScore(-0.25)
        );
        List<String> names = List.of("invalid-pos-inf", "invalid-neg-inf", "invalid-negative");
        for (int i = 0; i < scorers.size(); i++) {
            DetectionEvaluationRunner.DetectionEvaluationRun run =
                new DetectionEvaluationRunner(ReplayEngine.withEvaluationScorer(scorers.get(i)))
                    .evaluate(
                        trackedDatasetDirectory(),
                        trackedAnnotationsFile(),
                        candidateReplayConfiguration(names.get(i), "test", "bb".repeat(32)),
                        THRESHOLD,
                        tempDir.resolve(names.get(i))
                    );
            assertThat(run.metrics().evaluablePredictionCount()).isZero();
            assertThat(run.alignment().observations())
                .allSatisfy(observation -> assertThat(observation.prediction().anomalyScore()).isNull());
        }
    }

    @Test
    void poorCandidateQualityStillProducesStructurallyValidEvidence() throws Exception {
        DetectionEvaluationRunner.DetectionEvaluationRun run =
            new DetectionEvaluationRunner(ReplayEngine.withEvaluationScorer(fixedScore(0.0)))
                .evaluate(
                    trackedDatasetDirectory(),
                    trackedAnnotationsFile(),
                    candidateReplayConfiguration("poor-quality", "test", "cc".repeat(32)),
                    THRESHOLD,
                    tempDir.resolve("poor-quality")
                );

        assertThat(run.evidence().reportKind()).isEqualTo("diagnostic-detection-evaluation");
        assertThat(run.metrics().confusionMatrix().truePositives()).isZero();
        assertThat(run.metrics().confusionMatrix().falsePositives()).isZero();
        assertThat(run.metrics().evaluablePredictionCount()).isEqualTo(run.metrics().totalObservationCount());
        assertThat(run.writtenEvidence().jsonSha256()).hasSize(64);
    }

    @Test
    void candidateAlignmentStillRejectsDuplicateReplayIds() throws Exception {
        byte[] payload = encodeIsolationForest(5L);
        LoadedCandidateScorer loaded = loadReady(ifDescriptor("align-check", "1.0.0", "artifact-align", payload), payload);
        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult result =
            new CandidateDetectionEvaluationRunner().evaluateReady(
                loaded,
                trackedDatasetDirectory(),
                trackedAnnotationsFile(),
                THRESHOLD,
                tempDir.resolve("align-ok")
            );
        List<dev.aisentinel.core.replay.ReplayResult> duplicated = List.of(
            replayResultStub(result, 0),
            replayResultStub(result, 0)
        );
        assertThatThrownBy(() -> new ReferenceEvaluationAligner().align(
            new dev.aisentinel.core.replay.ReplayDatasetLoader().load(trackedDatasetDirectory(), trackedAnnotationsFile()),
            new dev.aisentinel.core.dataset.reference.ReferenceDatasetAnnotationsLoader().load(trackedAnnotationsFile()),
            duplicated,
            EvaluationPredictionSource.CANDIDATE_REPLAY_SCORE
        )).isInstanceOf(EvaluationAlignmentException.class)
            .hasMessageContaining("duplicate replay event id");
    }

    @Test
    void refusesOfficialBaselineOutputDirectory() {
        assertThatThrownBy(() -> new CandidateDetectionEvaluationRunner().evaluate(
            ifDescriptor("x", "1.0.0", "y", new byte[] {1}),
            new byte[] {1},
            trackedDatasetDirectory(),
            trackedAnnotationsFile(),
            THRESHOLD,
            officialBaselineDirectory()
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Official Detection Reference Baseline");
    }

    @Test
    void refusesOfficialBaselineChildOutputDirectory() {
        assertThatThrownBy(() -> new CandidateDetectionEvaluationRunner().evaluate(
            ifDescriptor("x", "1.0.0", "y", new byte[] {1}),
            new byte[] {1},
            trackedDatasetDirectory(),
            trackedAnnotationsFile(),
            THRESHOLD,
            officialBaselineDirectory().resolve("candidate-evaluation")
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Official Detection Reference Baseline");
    }

    @Test
    void candidateKindWithoutExplicitScorerDoesNotConstructARuntime() throws Exception {
        ReplayConfiguration configuration = candidateReplayConfiguration("missing-explicit", "1.0.0", "dd".repeat(32));
        assertThatThrownBy(() -> new ReplayEngine().run(
            new dev.aisentinel.core.replay.ReplayDatasetLoader().load(trackedDatasetDirectory(), trackedAnnotationsFile()),
            configuration,
            tempDir.resolve("missing-explicit")
        )).isInstanceOf(ReplayException.class)
            .hasMessageContaining("explicit evaluation scorer");
    }

    @Test
    void explicitEvaluationScorerCannotOverrideDefaultReplayConfiguration() throws Exception {
        assertThatThrownBy(() -> ReplayEngine.withEvaluationScorer(fixedScore(1.0)).run(
            new dev.aisentinel.core.replay.ReplayDatasetLoader().load(trackedDatasetDirectory(), trackedAnnotationsFile()),
            ReplayConfiguration.referenceDefaults(),
            tempDir.resolve("wrong-kind")
        )).isInstanceOf(ReplayException.class)
            .hasMessageContaining("requires CANDIDATE replay scorer configuration");
    }

    private CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult evaluateCandidate(
        ScorerArtifactDescriptor descriptor,
        byte[] payload,
        String outputName
    ) throws IOException {
        return evaluateCandidate(descriptor, payload, trackedAnnotationsFile(), outputName);
    }

    private CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult evaluateCandidate(
        ScorerArtifactDescriptor descriptor,
        byte[] payload,
        Path annotationsFile,
        String outputName
    ) throws IOException {
        return new CandidateDetectionEvaluationRunner().evaluate(
            descriptor,
            payload,
            trackedDatasetDirectory(),
            annotationsFile,
            THRESHOLD,
            tempDir.resolve(outputName)
        );
    }

    private void assertNotReady(
        ScorerArtifactDescriptor descriptor,
        byte[] payload,
        String outputName,
        CandidateScorerLoadFailureCode expected
    ) throws IOException {
        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult result =
            new CandidateDetectionEvaluationRunner().evaluate(
                descriptor,
                payload,
                trackedDatasetDirectory(),
                trackedAnnotationsFile(),
                THRESHOLD,
                tempDir.resolve(outputName)
            );
        assertThat(result.status()).isEqualTo(CandidateDetectionEvaluationStatus.CANDIDATE_NOT_READY);
        assertThat(result.alignment()).isEmpty();
        assertThat(result.metrics()).isEmpty();
        assertThat(result.temporal()).isEmpty();
        assertThat(result.evidence().evaluation()).isEmpty();
        assertThat(result.evidence().loadIssues()).extracting(CandidateLoadIssueRecord::code).contains(expected);
        assertThat(Files.exists(tempDir.resolve(outputName).resolve("candidate-evaluation.json"))).isTrue();
        assertThat(Files.readString(tempDir.resolve(outputName).resolve("candidate-evaluation.json")))
            .contains("\"evaluation\":null");
        assertThat(Files.exists(tempDir.resolve(outputName).resolve("evaluation.json"))).isFalse();
    }

    private static List<Double> predictionScores(
        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult result
    ) {
        return result.alignment().orElseThrow().observations().stream()
            .map(observation -> observation.prediction().anomalyScore())
            .toList();
    }

    private static ReplayConfiguration candidateReplayConfiguration(String scorerId, String version, String digestHex) {
        return new ReplayConfiguration(
            ReplaySchemas.REPLAY_MODE_FRESH_RUN,
            ReplayConfiguration.referenceDefaults().aiSentinelVersion(),
            ReplayScorerConfiguration.forCandidate(scorerId, version, digestHex),
            ReplayPolicyConfiguration.defaultThresholds()
        );
    }

    private static AnomalyScorer fixedScore(double score) {
        return new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return score;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
    }

    private static LoadedCandidateScorer loadReady(ScorerArtifactDescriptor descriptor, byte[] payload) {
        return dev.aisentinel.core.scoring.artifact.CandidateScorerLoader.load(descriptor, payload)
            .loaded()
            .orElseThrow();
    }

    private static byte[] encodeIsolationForest(long seed) throws Exception {
        IsolationForestTrainer trainer = new IsolationForestTrainer(5, 3, seed);
        IsolationForestModel model = trainer.train(List.of(
            new double[] {1, 2, 3, 4, 5},
            new double[] {2, 2, 2, 2, 2},
            new double[] {3, 3, 3, 3, 3}
        ));
        return IsolationForestModelCodec.encode(model);
    }

    private static ScorerArtifactDescriptor ifDescriptor(String scorerId, String version, String artifactId, byte[] payload) {
        return ifDescriptor(scorerId, version, artifactId, payload, ScorerArtifactDescriptor.FORMAT_AIF1);
    }

    private static ScorerArtifactDescriptor ifDescriptor(
        String scorerId,
        String version,
        String artifactId,
        byte[] payload,
        String format
    ) {
        return ScorerArtifactDescriptor.builder()
            .scorerId(scorerId)
            .scorerVersion(version)
            .artifactId(artifactId)
            .artifactFormat(format)
            .scorerType(ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1)
            .artifactDigest(ArtifactDigest.sha256Hex(TrainingFingerprintHashes.sha256HexBytes(payload)))
            .featureSchemaVersion(FeatureSchema.VERSION_ID)
            .requiredProjection(FeatureProjectionId.ISOLATION_FOREST)
            .requiredFeatureNames(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES)
            .declaredFeatureDimension(FeatureSchema.ISOLATION_FOREST_DIMENSION)
            .outputRange(ScorerOutputRange.unitInterval())
            .capabilities(new ScorerArtifactCapabilities(false, false, true))
            .build();
    }

    private static Path trackedDatasetDirectory() {
        return locateTrackedPath(ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY);
    }

    private static Path trackedAnnotationsFile() {
        return locateTrackedPath(ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE);
    }

    private static Path officialBaselineDirectory() {
        return locateTrackedPath(Path.of("evaluation/detection-reference-baseline"));
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

    private static String hashDirectory(Path directory) throws IOException {
        StringBuilder material = new StringBuilder();
        try (var walk = Files.walk(directory)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                material.append(directory.relativize(file)).append(':')
                    .append(TrainingFingerprintHashes.sha256HexBytes(Files.readAllBytes(file)))
                    .append('\n');
            }
        }
        return TrainingFingerprintHashes.sha256HexUtf8(material.toString());
    }

    private static dev.aisentinel.core.replay.ReplayResult replayResultStub(
        CandidateDetectionEvaluationRunner.CandidateDetectionEvaluationResult result,
        int index
    ) {
        EvaluationObservation observation = result.alignment().orElseThrow().observations().get(index);
        return new dev.aisentinel.core.replay.ReplayResult(
            "1",
            result.alignment().orElseThrow().replayRunId(),
            dev.aisentinel.core.replay.ReplayResultStatus.REPLAYED,
            observation.sequenceNumber(),
            observation.eventId(),
            "",
            observation.identityKey(),
            "",
            observation.observedAt(),
            "route:/api",
            FeatureSchema.VERSION_ID,
            result.evidence().candidate().scorerId(),
            result.evidence().candidate().scorerVersion(),
            observation.prediction().anomalyScore(),
            observation.prediction().policyScore(),
            observation.prediction().action(),
            observation.prediction().evaluationStatuses(),
            List.of(),
            "threshold-policy-default",
            "0.3.0",
            "MONITOR"
        );
    }
}
