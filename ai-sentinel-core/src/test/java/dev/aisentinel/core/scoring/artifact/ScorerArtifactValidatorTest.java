package dev.aisentinel.core.scoring.artifact;

import dev.aisentinel.core.model.FeatureProjectionId;
import dev.aisentinel.core.model.FeatureSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScorerArtifactValidatorTest {

    private static final String VALID_SHA256 =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void acceptsCompatibleIsolationForestCandidate() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1
        )
            .requiredProjection(FeatureProjectionId.ISOLATION_FOREST)
            .build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void acceptsStatisticalProjectionWithoutExplainabilityClaim() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.STATISTICAL_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_STATISTICAL
        )
            .capabilities(new ScorerArtifactCapabilities(true, true, true))
            .build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void rejectsUnsupportedFeatureSchema() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_EXTERNAL
        )
            .featureSchemaVersion("999")
            .build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.UNSUPPORTED_FEATURE_SCHEMA);
    }

    @Test
    void rejectsUnknownRequiredFeature() {
        List<String> features = new ArrayList<>(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES);
        features.set(0, "notARealFeature");
        ScorerArtifactDescriptor descriptor = baseBuilder(
            features,
            ScorerArtifactDescriptor.TYPE_EXTERNAL
        ).build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.UNKNOWN_REQUIRED_FEATURE);
    }

    @Test
    void rejectsDuplicateRequiredFeature() {
        List<String> features = new ArrayList<>(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES);
        features.set(1, features.get(0));
        ScorerArtifactDescriptor descriptor = baseBuilder(
            features,
            ScorerArtifactDescriptor.TYPE_EXTERNAL
        ).build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.DUPLICATE_REQUIRED_FEATURE);
    }

    @Test
    void rejectsFeatureDimensionMismatchEvenWhenListLooksSizedElsewhere() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_EXTERNAL
        )
            .declaredFeatureDimension(FeatureSchema.ISOLATION_FOREST_DIMENSION + 1)
            .build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.FEATURE_DIMENSION_MISMATCH);
        assertThat(result.issues().getFirst().message()).contains("same feature semantics");
    }

    @Test
    void rejectsSameDimensionWrongSemanticsWhenProjectionBound() {
        List<String> wrongSemantics = List.of(
            "headerFingerprintHash",
            "ipBucket",
            "endpointConcentration",
            "requestsPerWindow",
            "payloadSizeBytes"
        );
        assertThat(wrongSemantics).hasSize(FeatureSchema.ISOLATION_FOREST_DIMENSION);

        ScorerArtifactDescriptor descriptor = baseBuilder(
            wrongSemantics,
            ScorerArtifactDescriptor.TYPE_EXTERNAL
        )
            .requiredProjection(FeatureProjectionId.ISOLATION_FOREST)
            .build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);
        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.INCOMPATIBLE_FEATURE_ORDER);
    }

    @Test
    void rejectsWrongFeatureOrderForBoundProjection() {
        List<String> reordered = new ArrayList<>(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES);
        String first = reordered.removeFirst();
        reordered.add(first);

        ScorerArtifactDescriptor descriptor = baseBuilder(
            reordered,
            ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1
        )
            .requiredProjection(FeatureProjectionId.ISOLATION_FOREST)
            .build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);
        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.INCOMPATIBLE_FEATURE_ORDER);
    }

    @Test
    void rejectsIsolationForestTypeWithStatisticalProjectionEvenWhenProjectionOmitted() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.STATISTICAL_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1
        ).build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.INCOMPATIBLE_FEATURE_ORDER);
    }

    @Test
    void rejectsStatisticalTypeWithIsolationForestProjectionEvenWhenProjectionOmitted() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_STATISTICAL
        ).build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.INCOMPATIBLE_FEATURE_ORDER);
    }

    @Test
    void rejectsIncompatibleOutputRangeOutsideUnitInterval() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_EXTERNAL
        )
            .outputRange(new ScorerOutputRange(-1.0, 1.0))
            .build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.INCOMPATIBLE_OUTPUT_RANGE);
    }

    @Test
    void rejectsNonFiniteOutputBoundsAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ScorerOutputRange(Double.NaN, 1.0)
        ).isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ScorerOutputRange(0.0, Double.POSITIVE_INFINITY)
        ).isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new ScorerOutputRange(1.0, 0.0)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIsolationForestPerFeatureAttributionClaim() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1
        )
            .capabilities(new ScorerArtifactCapabilities(false, true, true))
            .build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.CONTRADICTORY_CAPABILITY_CLAIM);
    }

    @Test
    void rejectsPerFeatureAttributionWithoutExplainabilityClaim() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.STATISTICAL_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_EXTERNAL
        )
            .capabilities(new ScorerArtifactCapabilities(false, true, true))
            .build();

        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.accepted()).isFalse();
        assertThat(result.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.CONTRADICTORY_CAPABILITY_CLAIM);
    }

    @Test
    void absenceOfExplainabilityDoesNotInvalidateCandidate() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_EXTERNAL
        )
            .capabilities(ScorerArtifactCapabilities.none())
            .build();

        assertThat(ScorerArtifactValidator.validate(descriptor).accepted()).isTrue();
    }

    @Test
    void validateDigestMetadataRejectsUnsupportedAlgorithmAndMalformedHex() {
        assertThat(ScorerArtifactValidator.validateDigestMetadata("md5", VALID_SHA256).accepted())
            .isFalse();
        assertThat(ScorerArtifactValidator.validateDigestMetadata("SHA-256", null).accepted())
            .isFalse();
        assertThat(ScorerArtifactValidator.validateDigestMetadata("SHA-256", "zz").accepted())
            .isFalse();
        assertThat(ScorerArtifactValidator.validateDigestMetadata("SHA-256", VALID_SHA256).accepted())
            .isTrue();
    }

    @Test
    void validateIdentityTokensReportsStructuredFailures() {
        ScorerArtifactValidationResult blank = ScorerArtifactValidator.validateIdentityTokens(
            " ",
            null,
            "art",
            "binary",
            "external"
        );
        assertThat(blank.accepted()).isFalse();
        assertThat(blank.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(
                ScorerArtifactValidationIssueCode.MISSING_OR_BLANK_SCORER_ID,
                ScorerArtifactValidationIssueCode.MISSING_OR_BLANK_SCORER_VERSION
            );

        ScorerArtifactValidationResult invalid = ScorerArtifactValidator.validateIdentityTokens(
            "bad id!",
            "1.0",
            "art",
            "BINARY",
            "External"
        );
        // format/type normalize case; invalid scorerId still fails
        assertThat(invalid.accepted()).isFalse();
        assertThat(invalid.issues())
            .extracting(ScorerArtifactValidationIssue::code)
            .contains(ScorerArtifactValidationIssueCode.INVALID_SCORER_ID);
    }

    @Test
    void validationResultIsImmutableAndDoesNotImplyAttack() {
        ScorerArtifactDescriptor descriptor = baseBuilder(
            FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES,
            ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1
        )
            .featureSchemaVersion("999")
            .build();
        ScorerArtifactValidationResult result = ScorerArtifactValidator.validate(descriptor);

        assertThat(result.rejected()).isTrue();
        assertThatThrownByAdd(result);
        // Validation issues are configuration diagnostics only — no policy/enforcement mapping exists here.
        assertThat(result.issues()).allSatisfy(issue ->
            assertThat(issue.message()).doesNotContain("BLOCK", "QUARANTINE", "attack", "malicious")
        );
    }

    private static void assertThatThrownByAdd(ScorerArtifactValidationResult result) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> result.issues().add(
            new ScorerArtifactValidationIssue(
                ScorerArtifactValidationIssueCode.MALFORMED_DIGEST,
                "x"
            )
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    private static ScorerArtifactDescriptor.Builder baseBuilder(List<String> features, String type) {
        return ScorerArtifactDescriptor.builder()
            .scorerId("candidate-scorer")
            .scorerVersion("1.0.0")
            .artifactId("artifact-1")
            .artifactFormat("binary")
            .scorerType(type)
            .artifactDigest(ArtifactDigest.sha256Hex(VALID_SHA256))
            .featureSchemaVersion(FeatureSchema.VERSION_ID)
            .requiredFeatureNames(features)
            .declaredFeatureDimension(features.size())
            .outputRange(ScorerOutputRange.unitInterval())
            .capabilities(ScorerArtifactCapabilities.none());
    }
}
