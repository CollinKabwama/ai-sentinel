package dev.aisentinel.core.scoring.artifact;

import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.IsolationForestModel;
import dev.aisentinel.core.scoring.IsolationForestModelCodec;
import dev.aisentinel.core.scoring.IsolationForestTrainer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateScorerLoaderTest {

    @Test
    void notConfiguredWhenNoCandidateSupplied() {
        CandidateScorerLoadResult result = CandidateScorerLoader.notConfigured();
        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.NOT_CONFIGURED);
        assertThat(result.ready()).isFalse();
        assertThat(result.loaded()).isEmpty();
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .containsExactly(CandidateScorerLoadFailureCode.NOT_CONFIGURED);
    }

    @Test
    void loadsMatchingIsolationForestArtifactToReady() throws Exception {
        byte[] payload = encodeTrainedIsolationForest();
        ScorerArtifactDescriptor descriptor = ifDescriptor(payload);

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, payload);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.READY);
        assertThat(result.ready()).isTrue();
        LoadedCandidateScorer loaded = result.loaded().orElseThrow();
        assertThat(loaded.descriptor()).isSameAs(descriptor);
        assertThat(loaded.verifiedArtifact().computedDigestHex())
            .isEqualTo(descriptor.artifactDigest().digestHex());
        assertThat(loaded.provenance().artifactBytesVerified()).isTrue();
        assertThat(loaded.provenance().configurationFingerprintSha256Hex())
            .isEqualTo(descriptor.configurationFingerprintSha256Hex());
        assertThat(loaded.provenance().runtimeImplementationId())
            .isEqualTo(CandidateIsolationForestScorer.RUNTIME_IMPLEMENTATION_ID);

        double score = loaded.scorer().score(sampleFeatures());
        assertThat(score).isBetween(0.0, 1.0);
        assertThat(Double.isNaN(score)).isFalse();
    }

    @Test
    void candidateIsolationForestScoreMatchesDecodedModelScore() throws Exception {
        byte[] payload = encodeTrainedIsolationForest();
        RequestFeatures features = sampleFeatures();

        LoadedCandidateScorer loaded = CandidateScorerLoader.load(ifDescriptor(payload), payload)
            .loaded().orElseThrow();
        IsolationForestModel decoded = IsolationForestModelCodec.decode(payload);

        assertThat(loaded.scorer().score(features))
            .isEqualTo(decoded.score(features.toIsolationForestArray()));
    }

    @Test
    void digestMismatchPreventsConstruction() throws Exception {
        byte[] payload = encodeTrainedIsolationForest();
        ScorerArtifactDescriptor descriptor = ifDescriptor(new byte[] {1, 2, 3});

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, payload);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.INVALID);
        assertThat(result.loaded()).isEmpty();
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.DIGEST_MISMATCH);
        assertThat(result.provenance().orElseThrow().artifactBytesVerified()).isFalse();
    }

    @Test
    void emptyArtifactIsInvalid() {
        byte[] empty = new byte[0];
        ScorerArtifactDescriptor descriptor = ifDescriptor(empty);

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, empty);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.INVALID);
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.ARTIFACT_EMPTY);
        assertThat(result.loaded()).isEmpty();
    }

    @Test
    void nullArtifactBytesAreUnavailable() throws Exception {
        byte[] payload = encodeTrainedIsolationForest();
        ScorerArtifactDescriptor descriptor = ifDescriptor(payload);

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, null);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.UNAVAILABLE);
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.ARTIFACT_UNAVAILABLE);
        assertThat(result.loaded()).isEmpty();
    }

    @Test
    void oversizedArtifactRejectedBeforeConstruction() {
        byte[] huge = new byte[ArtifactByteIntegrity.MAX_CANDIDATE_ARTIFACT_BYTES + 1];
        ScorerArtifactDescriptor descriptor = ifDescriptor(new byte[] {1});

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, huge);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.INVALID);
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.ARTIFACT_TOO_LARGE);
        assertThat(result.loaded()).isEmpty();
    }

    @Test
    void verifiedBytesEqualConsumedBytesDespiteCallerMutation() throws Exception {
        byte[] payload = encodeTrainedIsolationForest();
        ScorerArtifactDescriptor descriptor = ifDescriptor(payload);

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, payload);
        assertThat(result.ready()).isTrue();

        payload[0] = (byte) (payload[0] ^ 0xFF);

        byte[] verifiedCopy = result.loaded().orElseThrow().verifiedArtifact().copyOfBytes();
        assertThat(TrainingFingerprintHashes.sha256HexBytes(verifiedCopy))
            .isEqualTo(descriptor.artifactDigest().digestHex());
        assertThat(result.loaded().orElseThrow().scorer().score(sampleFeatures())).isBetween(0.0, 1.0);
    }

    @Test
    void verifiedArtifactCopyIsDefensivelyIsolated() throws Exception {
        byte[] payload = encodeTrainedIsolationForest();
        ScorerArtifactDescriptor descriptor = ifDescriptor(payload);
        VerifiedArtifactBytes verified = CandidateScorerLoader.load(descriptor, payload)
            .loaded().orElseThrow().verifiedArtifact();

        byte[] copy = verified.copyOfBytes();
        copy[0] = (byte) (copy[0] ^ 0xFF);
        assertThat(TrainingFingerprintHashes.sha256HexBytes(verified.copyOfBytes()))
            .isEqualTo(descriptor.artifactDigest().digestHex());
    }

    @Test
    void unsupportedScorerTypeRemainsUnavailableAfterDigestMatch() {
        byte[] payload = new byte[] {1, 2, 3, 4};
        ScorerArtifactDescriptor descriptor = ScorerArtifactDescriptor.builder()
            .scorerId("external-candidate")
            .scorerVersion("1.0.0")
            .artifactId("ext-1")
            .artifactFormat("binary")
            .scorerType(ScorerArtifactDescriptor.TYPE_EXTERNAL)
            .artifactDigest(ArtifactDigest.sha256Hex(TrainingFingerprintHashes.sha256HexBytes(payload)))
            .featureSchemaVersion(FeatureSchema.VERSION_ID)
            .requiredFeatureNames(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES)
            .declaredFeatureDimension(FeatureSchema.ISOLATION_FOREST_DIMENSION)
            .outputRange(ScorerOutputRange.unitInterval())
            .capabilities(new ScorerArtifactCapabilities(false, false, true))
            .build();

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, payload);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.UNAVAILABLE);
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.UNSUPPORTED_RUNTIME_IMPLEMENTATION);
        assertThat(result.provenance().orElseThrow().artifactBytesVerified()).isTrue();
        assertThat(result.loaded()).isEmpty();
    }

    @Test
    void exactMaximumArtifactSizeIsAcceptedBeforeUnsupportedRuntimeDispatch() {
        byte[] payload = new byte[ArtifactByteIntegrity.MAX_CANDIDATE_ARTIFACT_BYTES];
        ScorerArtifactDescriptor descriptor = ScorerArtifactDescriptor.builder()
            .scorerId("external-candidate")
            .scorerVersion("1.0.0")
            .artifactId("ext-max")
            .artifactFormat("binary")
            .scorerType(ScorerArtifactDescriptor.TYPE_EXTERNAL)
            .artifactDigest(ArtifactDigest.sha256Hex(TrainingFingerprintHashes.sha256HexBytes(payload)))
            .featureSchemaVersion(FeatureSchema.VERSION_ID)
            .requiredFeatureNames(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES)
            .declaredFeatureDimension(FeatureSchema.ISOLATION_FOREST_DIMENSION)
            .outputRange(ScorerOutputRange.unitInterval())
            .capabilities(new ScorerArtifactCapabilities(false, false, true))
            .build();

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, payload);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.UNAVAILABLE);
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.UNSUPPORTED_RUNTIME_IMPLEMENTATION);
        assertThat(result.provenance().orElseThrow().artifactBytesVerified()).isTrue();
    }

    @Test
    void unsupportedIsolationForestArtifactFormatDoesNotRouteToDecoder() throws Exception {
        byte[] payload = encodeTrainedIsolationForest();
        ScorerArtifactDescriptor descriptor = ifDescriptor(payload, "binary");

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, payload);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.UNAVAILABLE);
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.UNSUPPORTED_ARTIFACT_FORMAT);
        assertThat(result.provenance().orElseThrow().artifactBytesVerified()).isTrue();
        assertThat(result.loaded()).isEmpty();
    }

    @Test
    void invalidDescriptorRejectedBeforeByteVerification() {
        ScorerArtifactDescriptor descriptor = ScorerArtifactDescriptor.builder()
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

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, new byte[] {1});

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.INVALID);
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.DESCRIPTOR_REJECTED);
        assertThat(result.loaded()).isEmpty();
    }

    @Test
    void decodeFailureContainedAsInvalid() throws Exception {
        byte[] garbage = new byte[] {'N', 'O', 'P', 'E', 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        ScorerArtifactDescriptor descriptor = ifDescriptor(garbage);

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, garbage);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.INVALID);
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.ARTIFACT_DECODE_FAILED);
        assertThat(result.provenance().orElseThrow().artifactBytesVerified()).isTrue();
        assertThat(result.loaded()).isEmpty();
    }

    @Test
    void excessivelyDeepModelPayloadFailsAsDecodeFailure() throws Exception {
        byte[] payload = tooDeepAif1Payload(1_100);
        ScorerArtifactDescriptor descriptor = ifDescriptor(payload);

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, payload);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.INVALID);
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.ARTIFACT_DECODE_FAILED);
        assertThat(result.provenance().orElseThrow().artifactBytesVerified()).isTrue();
        assertThat(result.loaded()).isEmpty();
    }

    @Test
    void featureDimensionMismatchPreventsReady() throws Exception {
        IsolationForestTrainer trainer = new IsolationForestTrainer(3, 2, 7L);
        IsolationForestModel model = trainer.train(List.of(
            new double[] {1, 2, 3},
            new double[] {2, 2, 2},
            new double[] {3, 3, 3}
        ));
        byte[] payload = IsolationForestModelCodec.encode(model);
        ScorerArtifactDescriptor descriptor = ifDescriptor(payload);

        CandidateScorerLoadResult result = CandidateScorerLoader.load(descriptor, payload);

        assertThat(result.status()).isEqualTo(CandidateScorerRuntimeStatus.INVALID);
        assertThat(result.issues()).extracting(CandidateScorerLoadIssue::code)
            .contains(CandidateScorerLoadFailureCode.FEATURE_DIMENSION_MISMATCH);
        assertThat(result.loaded()).isEmpty();
    }

    @Test
    void loadFailureDoesNotFabricateScoreOrMutateBaseline() throws Exception {
        StatisticalScorer statistical = new StatisticalScorer();
        RequestFeatures features = sampleFeatures();
        statistical.update(features);
        double before = statistical.score(features);

        byte[] payload = encodeTrainedIsolationForest();
        ScorerArtifactDescriptor mismatched = ifDescriptor(new byte[] {9, 9, 9});
        CandidateScorerLoadResult result = CandidateScorerLoader.load(mismatched, payload);

        assertThat(result.ready()).isFalse();
        assertThat(result.loaded()).isEmpty();
        assertThat(statistical.score(features)).isEqualTo(before);
    }

    @Test
    void candidateUpdateDoesNotTrainBehavioralState() throws Exception {
        byte[] payload = encodeTrainedIsolationForest();
        LoadedCandidateScorer loaded = CandidateScorerLoader.load(ifDescriptor(payload), payload)
            .loaded().orElseThrow();

        RequestFeatures features = sampleFeatures();
        double first = loaded.scorer().score(features);
        loaded.scorer().update(features);
        loaded.scorer().update(features);
        assertThat(loaded.scorer().score(features)).isEqualTo(first);
    }

    @Test
    void sameArtifactAndDescriptorYieldSameProvenanceFingerprints() throws Exception {
        byte[] payload = encodeTrainedIsolationForest();
        ScorerArtifactDescriptor descriptor = ifDescriptor(payload);

        CandidateScorerProvenance first = CandidateScorerLoader.load(descriptor, payload)
            .provenance().orElseThrow();
        CandidateScorerProvenance second = CandidateScorerLoader.load(descriptor, payload)
            .provenance().orElseThrow();

        assertThat(first.configurationFingerprintSha256Hex())
            .isEqualTo(second.configurationFingerprintSha256Hex())
            .isEqualTo(descriptor.configurationFingerprintSha256Hex());
        assertThat(first.verifiedDigestHex()).isEqualTo(second.verifiedDigestHex());
        assertThat(first.verifiedDigestHex()).isEqualTo(descriptor.artifactDigest().digestHex());
        assertThat(first.configurationFingerprintSha256Hex())
            .isNotEqualTo(first.verifiedDigestHex());
    }

    @Test
    void nonReadyResultsRequireIssues() throws Exception {
        byte[] payload = encodeTrainedIsolationForest();
        ScorerArtifactDescriptor descriptor = ifDescriptor(payload);
        CandidateScorerProvenance provenance = new CandidateScorerProvenance(descriptor, false, "", "");

        assertThatThrownBy(() -> CandidateScorerLoadResult.invalid(descriptor, provenance, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-READY results must carry at least one issue");
        assertThatThrownBy(() -> CandidateScorerLoadResult.unavailable(descriptor, provenance, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("non-READY results must carry at least one issue");
    }

    private static byte[] encodeTrainedIsolationForest() throws Exception {
        IsolationForestTrainer trainer = new IsolationForestTrainer(5, 3, 42L);
        IsolationForestModel model = trainer.train(List.of(
            new double[] {1, 2, 3, 4, 5},
            new double[] {2, 2, 2, 2, 2},
            new double[] {3, 3, 3, 3, 3}
        ));
        return IsolationForestModelCodec.encode(model);
    }

    private static ScorerArtifactDescriptor ifDescriptor(byte[] payload) {
        return ifDescriptor(payload, ScorerArtifactDescriptor.FORMAT_AIF1);
    }

    private static ScorerArtifactDescriptor ifDescriptor(byte[] payload, String artifactFormat) {
        return ScorerArtifactDescriptor.builder()
            .scorerId("research-if-candidate")
            .scorerVersion("1.0.0")
            .artifactId("artifact-1")
            .artifactFormat(artifactFormat)
            .scorerType(ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1)
            .artifactDigest(ArtifactDigest.sha256Hex(TrainingFingerprintHashes.sha256HexBytes(payload)))
            .featureSchemaVersion(FeatureSchema.VERSION_ID)
            .requiredFeatureNames(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES)
            .declaredFeatureDimension(FeatureSchema.ISOLATION_FOREST_DIMENSION)
            .outputRange(ScorerOutputRange.unitInterval())
            .capabilities(new ScorerArtifactCapabilities(false, false, true))
            .build();
    }

    private static byte[] tooDeepAif1Payload(int branchDepth) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.write(new byte[] {'A', 'I', 'F', '1'});
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(1);
        out.writeInt(FeatureSchema.ISOLATION_FOREST_DIMENSION);
        for (int i = 0; i < branchDepth; i++) {
            out.writeByte(1);
            out.writeInt(0);
            out.writeDouble(0.0);
        }
        out.writeByte(0);
        out.writeInt(1);
        for (int i = 0; i < branchDepth; i++) {
            out.writeByte(0);
            out.writeInt(1);
        }
        out.flush();
        return bos.toByteArray();
    }

    private static RequestFeatures sampleFeatures() {
        return RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(2)
            .endpointEntropy(0.5)
            .endpointConcentration(0.5)
            .tokenAgeSeconds(60)
            .parameterCount(1)
            .payloadSizeBytes(100)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }
}
