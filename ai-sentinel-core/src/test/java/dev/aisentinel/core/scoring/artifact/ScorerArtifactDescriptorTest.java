package dev.aisentinel.core.scoring.artifact;

import dev.aisentinel.core.model.FeatureSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScorerArtifactDescriptorTest {

    private static final String VALID_SHA256 =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void buildsImmutableDescriptorWithDeterministicFingerprint() {
        List<String> features = new ArrayList<>(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES);
        ScorerArtifactDescriptor descriptor = validBuilder(features).build();

        assertThat(descriptor.scorerId()).isEqualTo("research-if-candidate");
        assertThat(descriptor.requiredFeatureNames()).containsExactlyElementsOf(
            FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES
        );
        assertThat(descriptor.declaredFeatureDimension())
            .isEqualTo(FeatureSchema.ISOLATION_FOREST_DIMENSION);
        assertThat(descriptor.configurationFingerprintSha256Hex()).hasSize(64);

        features.clear();
        assertThat(descriptor.requiredFeatureNames()).containsExactlyElementsOf(
            FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES
        );
        assertThatThrownBy(() -> descriptor.requiredFeatureNames().add("requestsPerWindow"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sameInputsProduceSameFingerprint() {
        ScorerArtifactDescriptor first = validBuilder(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES).build();
        ScorerArtifactDescriptor second = validBuilder(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES).build();
        assertThat(first.configurationFingerprintSha256Hex())
            .isEqualTo(second.configurationFingerprintSha256Hex());
        assertThat(first.canonicalConfigurationPayload()).isEqualTo(second.canonicalConfigurationPayload());
    }

    @Test
    void fingerprintUsesUnambiguousLengthPrefixedPayload() {
        ScorerArtifactDescriptor descriptor = validBuilder(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES).build();

        assertThat(descriptor.canonicalConfigurationPayload())
            .contains("8:scorerId=21:research-if-candidate")
            .contains("16:requiredFeatures=5:17:requestsPerWindow");
    }

    @Test
    void fingerprintChangesWhenBehaviorallyRelevantFieldsChange() {
        ScorerArtifactDescriptor first = validBuilder(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES).build();
        ScorerArtifactDescriptor differentVersion = validBuilder(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES)
            .scorerVersion("1.0.1")
            .build();
        ScorerArtifactDescriptor differentDigest = validBuilder(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES)
            .artifactDigest(ArtifactDigest.sha256Hex(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
            ))
            .build();

        assertThat(first.configurationFingerprintSha256Hex())
            .isNotEqualTo(differentVersion.configurationFingerprintSha256Hex())
            .isNotEqualTo(differentDigest.configurationFingerprintSha256Hex());
    }

    @Test
    void rejectsBlankScorerId() {
        assertThatThrownBy(() -> validBuilder(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES)
            .scorerId("  ")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scorerId");
    }

    @Test
    void rejectsBlankScorerVersion() {
        assertThatThrownBy(() -> validBuilder(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES)
            .scorerVersion("")
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scorerVersion");
    }

    @Test
    void rejectsNullDigest() {
        assertThatThrownBy(() -> ScorerArtifactDescriptor.builder()
            .scorerId("id")
            .scorerVersion("1.0.0")
            .artifactId("art-1")
            .artifactFormat("binary")
            .scorerType(ScorerArtifactDescriptor.TYPE_EXTERNAL)
            .featureSchemaVersion(FeatureSchema.VERSION_ID)
            .requiredFeatureNames(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES)
            .build())
            .isInstanceOf(NullPointerException.class);
    }

    private static ScorerArtifactDescriptor.Builder validBuilder(List<String> features) {
        return ScorerArtifactDescriptor.builder()
            .scorerId("research-if-candidate")
            .scorerVersion("1.0.0")
            .artifactId("artifact-1")
            .artifactFormat("binary")
            .scorerType(ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1)
            .artifactDigest(ArtifactDigest.sha256Hex(VALID_SHA256))
            .featureSchemaVersion(FeatureSchema.VERSION_ID)
            .requiredFeatureNames(features)
            .declaredFeatureDimension(features.size())
            .outputRange(ScorerOutputRange.unitInterval())
            .capabilities(new ScorerArtifactCapabilities(false, false, true));
    }
}
