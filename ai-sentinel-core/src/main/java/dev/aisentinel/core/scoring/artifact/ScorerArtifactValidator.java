package dev.aisentinel.core.scoring.artifact;

import dev.aisentinel.core.model.FeatureDefinition;
import dev.aisentinel.core.model.FeatureSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates candidate scorer/model artifact descriptors against the current
 * AI-Sentinel feature schema and anomaly-scorer output contract.
 * <p>
 * This answers whether declared metadata is acceptable. It does not load
 * artifact bytes, execute scorers, assess detection quality, or assign runtime
 * health. Matching digests to artifact bytes belongs to {@link CandidateScorerLoader}.
 * <p>
 * Rejection is an engineering/configuration outcome and must not be interpreted
 * as attack evidence ({@code INFRASTRUCTURE FAILURE != ATTACK}).
 */
public final class ScorerArtifactValidator {

    private ScorerArtifactValidator() {
    }

    /**
     * Validate a fully constructed descriptor.
     */
    public static ScorerArtifactValidationResult validate(ScorerArtifactDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        List<ScorerArtifactValidationIssue> issues = new ArrayList<>();
        validateDescriptorSchema(descriptor.descriptorSchemaVersion(), issues);
        validateFeatureSchemaBinding(descriptor, issues);
        validateRequiredFeatures(descriptor, issues);
        validateProjectionBinding(descriptor, issues);
        validateKnownScorerTypeFeatureContract(descriptor, issues);
        validateOutputRange(descriptor.outputRange(), issues);
        validateCapabilityClaims(descriptor, issues);
        if (issues.isEmpty()) {
            return ScorerArtifactValidationResult.accept();
        }
        return ScorerArtifactValidationResult.reject(issues);
    }

    /**
     * Validate digest metadata without hashing artifact bytes.
     */
    public static ScorerArtifactValidationResult validateDigestMetadata(String algorithm, String digestHex) {
        List<ScorerArtifactValidationIssue> issues = new ArrayList<>();
        if (algorithm == null || algorithm.isBlank()) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.UNSUPPORTED_DIGEST_ALGORITHM,
                "digest algorithm must not be blank"
            ));
            return ScorerArtifactValidationResult.reject(issues);
        }
        ArtifactDigestAlgorithm parsed;
        try {
            parsed = ArtifactDigestAlgorithm.parse(algorithm);
        } catch (IllegalArgumentException ex) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.UNSUPPORTED_DIGEST_ALGORITHM,
                "unsupported digest algorithm"
            ));
            return ScorerArtifactValidationResult.reject(issues);
        }
        if (digestHex == null || digestHex.isBlank()) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.MISSING_ARTIFACT_DIGEST,
                "artifact digest must not be blank"
            ));
            return ScorerArtifactValidationResult.reject(issues);
        }
        try {
            new ArtifactDigest(parsed, digestHex);
        } catch (IllegalArgumentException ex) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.MALFORMED_DIGEST,
                "artifact digest metadata is malformed for " + parsed.wireName()
            ));
            return ScorerArtifactValidationResult.reject(issues);
        }
        return ScorerArtifactValidationResult.accept();
    }

    private static void validateDescriptorSchema(int version, List<ScorerArtifactValidationIssue> issues) {
        if (version != ScorerArtifactDescriptor.DESCRIPTOR_SCHEMA_VERSION) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.INVALID_DESCRIPTOR_SCHEMA,
                "unsupported scorer artifact descriptor schema version: " + version
            ));
        }
    }

    private static void validateFeatureSchemaBinding(
        ScorerArtifactDescriptor descriptor,
        List<ScorerArtifactValidationIssue> issues
    ) {
        if (!FeatureSchema.supportsVersion(descriptor.featureSchemaVersion())) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.UNSUPPORTED_FEATURE_SCHEMA,
                "unsupported feature schema version: " + descriptor.featureSchemaVersion()
            ));
        }
    }

    private static void validateRequiredFeatures(
        ScorerArtifactDescriptor descriptor,
        List<ScorerArtifactValidationIssue> issues
    ) {
        List<String> required = descriptor.requiredFeatureNames();
        if (required.isEmpty()) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.EMPTY_REQUIRED_FEATURES,
                "requiredFeatureNames must not be empty"
            ));
            return;
        }
        if (descriptor.declaredFeatureDimension() != required.size()) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.FEATURE_DIMENSION_MISMATCH,
                "declaredFeatureDimension "
                    + descriptor.declaredFeatureDimension()
                    + " does not match requiredFeatureNames size "
                    + required.size()
                    + "; same dimension does not imply same feature semantics"
            ));
        }

        Set<String> canonicalNames = canonicalFeatureNames();
        Set<String> seen = new HashSet<>();
        for (String featureName : required) {
            if (featureName == null || featureName.isBlank()) {
                issues.add(issue(
                    ScorerArtifactValidationIssueCode.BLANK_REQUIRED_FEATURE,
                    "requiredFeatureNames must not contain blank names"
                ));
                continue;
            }
            if (!seen.add(featureName)) {
                issues.add(issue(
                    ScorerArtifactValidationIssueCode.DUPLICATE_REQUIRED_FEATURE,
                    "requiredFeatureNames must not contain duplicates: " + featureName
                ));
            }
            if (FeatureSchema.supportsVersion(descriptor.featureSchemaVersion())
                && !canonicalNames.contains(featureName)) {
                issues.add(issue(
                    ScorerArtifactValidationIssueCode.UNKNOWN_REQUIRED_FEATURE,
                    "unknown required feature for feature schema "
                        + descriptor.featureSchemaVersion()
                        + ": "
                        + featureName
                ));
            }
        }
    }

    private static void validateProjectionBinding(
        ScorerArtifactDescriptor descriptor,
        List<ScorerArtifactValidationIssue> issues
    ) {
        if (descriptor.requiredProjection() == null) {
            return;
        }
        if (!FeatureSchema.supportsVersion(descriptor.featureSchemaVersion())) {
            return;
        }
        List<String> expected = FeatureSchema.projection(descriptor.requiredProjection()).orderedFeatureNames();
        List<String> actual = descriptor.requiredFeatureNames();
        if (!expected.equals(actual)) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.INCOMPATIBLE_FEATURE_ORDER,
                "requiredFeatureNames must exactly match ordered projection "
                    + descriptor.requiredProjection()
                    + "; same dimension does not imply same feature semantics"
            ));
        }
    }

    private static void validateOutputRange(
        ScorerOutputRange outputRange,
        List<ScorerArtifactValidationIssue> issues
    ) {
        if (!outputRange.isCompatibleWithAnomalyScorerContract()) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.INCOMPATIBLE_OUTPUT_RANGE,
                "output range must lie within AnomalyScorer contract [0.0, 1.0]"
            ));
        }
    }

    private static void validateKnownScorerTypeFeatureContract(
        ScorerArtifactDescriptor descriptor,
        List<ScorerArtifactValidationIssue> issues
    ) {
        if (!FeatureSchema.supportsVersion(descriptor.featureSchemaVersion())) {
            return;
        }
        List<String> expected = null;
        if (ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1.equals(descriptor.scorerType())) {
            expected = FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES;
        } else if (ScorerArtifactDescriptor.TYPE_STATISTICAL.equals(descriptor.scorerType())) {
            expected = FeatureSchema.STATISTICAL_FEATURE_NAMES;
        }
        if (expected != null && !expected.equals(descriptor.requiredFeatureNames())) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.INCOMPATIBLE_FEATURE_ORDER,
                descriptor.scorerType()
                    + " requiredFeatureNames must exactly match the current ordered scorer projection"
            ));
        }
    }

    private static void validateCapabilityClaims(
        ScorerArtifactDescriptor descriptor,
        List<ScorerArtifactValidationIssue> issues
    ) {
        if (descriptor.capabilities().supportsPerFeatureAttribution()
            && !descriptor.capabilities().supportsExplainability()) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.CONTRADICTORY_CAPABILITY_CLAIM,
                "per-feature attribution requires an explainability capability claim"
            ));
        }
        if (ScorerArtifactDescriptor.TYPE_ISOLATION_FOREST_V1.equals(descriptor.scorerType())
            && descriptor.capabilities().supportsPerFeatureAttribution()) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.CONTRADICTORY_CAPABILITY_CLAIM,
                "isolation_forest_v1 artifacts must not claim per-feature attribution"
            ));
        }
    }

    private static Set<String> canonicalFeatureNames() {
        Set<String> names = new HashSet<>();
        for (FeatureDefinition definition : FeatureSchema.CANONICAL_FEATURES) {
            names.add(definition.name());
        }
        return names;
    }

    private static ScorerArtifactValidationIssue issue(ScorerArtifactValidationIssueCode code, String message) {
        return new ScorerArtifactValidationIssue(code, message);
    }

    /**
     * Convenience: build and validate in one step for trusted construction paths.
     * Construction identity failures still throw {@link IllegalArgumentException}.
     */
    public static ScorerArtifactValidationResult validateBuilt(ScorerArtifactDescriptor.Builder builder) {
        Objects.requireNonNull(builder, "builder");
        return validate(builder.build());
    }

    /**
     * Soft-validate untrusted identity/format tokens without constructing a descriptor.
     */
    public static ScorerArtifactValidationResult validateIdentityTokens(
        String scorerId,
        String scorerVersion,
        String artifactId,
        String artifactFormat,
        String scorerType
    ) {
        List<ScorerArtifactValidationIssue> issues = new ArrayList<>();
        validateIdentityToken(
            scorerId,
            ScorerArtifactValidationIssueCode.MISSING_OR_BLANK_SCORER_ID,
            ScorerArtifactValidationIssueCode.INVALID_SCORER_ID,
            "scorerId",
            issues
        );
        validateIdentityToken(
            scorerVersion,
            ScorerArtifactValidationIssueCode.MISSING_OR_BLANK_SCORER_VERSION,
            ScorerArtifactValidationIssueCode.INVALID_SCORER_VERSION,
            "scorerVersion",
            issues
        );
        validateIdentityToken(
            artifactId,
            ScorerArtifactValidationIssueCode.MISSING_OR_BLANK_ARTIFACT_ID,
            ScorerArtifactValidationIssueCode.INVALID_ARTIFACT_ID,
            "artifactId",
            issues
        );
        if (artifactFormat == null || artifactFormat.isBlank()) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.MISSING_OR_BLANK_ARTIFACT_FORMAT,
                "artifactFormat must not be blank"
            ));
        } else {
            try {
                ScorerArtifactDescriptor.requireArtifactFormat(artifactFormat);
            } catch (IllegalArgumentException ex) {
                issues.add(issue(
                    ScorerArtifactValidationIssueCode.INVALID_ARTIFACT_FORMAT,
                    "artifactFormat has invalid format"
                ));
            }
        }
        if (scorerType == null || scorerType.isBlank()) {
            issues.add(issue(
                ScorerArtifactValidationIssueCode.MISSING_OR_BLANK_SCORER_TYPE,
                "scorerType must not be blank"
            ));
        } else {
            try {
                ScorerArtifactDescriptor.requireScorerType(scorerType);
            } catch (IllegalArgumentException ex) {
                issues.add(issue(
                    ScorerArtifactValidationIssueCode.INVALID_SCORER_TYPE,
                    "scorerType has invalid format"
                ));
            }
        }
        if (issues.isEmpty()) {
            return ScorerArtifactValidationResult.accept();
        }
        return ScorerArtifactValidationResult.reject(issues);
    }

    private static void validateIdentityToken(
        String value,
        ScorerArtifactValidationIssueCode blankCode,
        ScorerArtifactValidationIssueCode invalidCode,
        String fieldName,
        List<ScorerArtifactValidationIssue> issues
    ) {
        if (value == null || value.isBlank()) {
            issues.add(issue(blankCode, fieldName + " must not be blank"));
            return;
        }
        try {
            ScorerArtifactDescriptor.requireIdentityToken(value, fieldName);
        } catch (IllegalArgumentException ex) {
            issues.add(issue(invalidCode, fieldName + " has invalid format"));
        }
    }
}
