package dev.aisentinel.core.scoring.artifact;

import dev.aisentinel.core.model.FeatureProjectionId;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable declared contract for a candidate scorer/model artifact.
 * <p>
 * This is engineering metadata describing identity, integrity, feature-schema
 * binding, input requirements, output range, and capability claims. It is not
 * runtime health state and not proof of model quality.
 * <p>
 * {@code BASELINE CANDIDATE != MODEL CANDIDATE}: this descriptor is for
 * scorer/model artifacts, not Official Detection Reference Baseline candidates.
 * <p>
 * {@code DESCRIPTOR VALID != RUNTIME AVAILABLE}.
 */
public final class ScorerArtifactDescriptor {

    public static final int DESCRIPTOR_SCHEMA_VERSION = 1;

    /**
     * Well-known scorer/model type identifiers. Additional validated identifiers
     * are allowed for forward compatibility with externally researched types.
     */
    public static final String TYPE_STATISTICAL = "statistical";
    public static final String TYPE_ISOLATION_FOREST_V1 = "isolation_forest_v1";
    public static final String TYPE_COMPOSITE = "composite";
    public static final String TYPE_EXTERNAL = "external";

    /** Current binary Isolation Forest artifact format understood by the runtime loader. */
    public static final String FORMAT_AIF1 = "aif1";

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern TYPE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");
    private static final int MAX_FEATURE_COUNT = 256;

    private final int descriptorSchemaVersion;
    private final String scorerId;
    private final String scorerVersion;
    private final String artifactId;
    private final String artifactFormat;
    private final String scorerType;
    private final ArtifactDigest artifactDigest;
    private final String featureSchemaVersion;
    private final FeatureProjectionId requiredProjection;
    private final List<String> requiredFeatureNames;
    private final int declaredFeatureDimension;
    private final ScorerOutputRange outputRange;
    private final ScorerArtifactCapabilities capabilities;

    private ScorerArtifactDescriptor(Builder builder) {
        this.descriptorSchemaVersion = builder.descriptorSchemaVersion;
        this.scorerId = builder.scorerId;
        this.scorerVersion = builder.scorerVersion;
        this.artifactId = builder.artifactId;
        this.artifactFormat = builder.artifactFormat;
        this.scorerType = builder.scorerType;
        this.artifactDigest = builder.artifactDigest;
        this.featureSchemaVersion = builder.featureSchemaVersion;
        this.requiredProjection = builder.requiredProjection;
        this.requiredFeatureNames = List.copyOf(builder.requiredFeatureNames);
        this.declaredFeatureDimension = builder.declaredFeatureDimension;
        this.outputRange = builder.outputRange;
        this.capabilities = builder.capabilities;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int descriptorSchemaVersion() {
        return descriptorSchemaVersion;
    }

    public String scorerId() {
        return scorerId;
    }

    public String scorerVersion() {
        return scorerVersion;
    }

    public String artifactId() {
        return artifactId;
    }

    public String artifactFormat() {
        return artifactFormat;
    }

    public String scorerType() {
        return scorerType;
    }

    public ArtifactDigest artifactDigest() {
        return artifactDigest;
    }

    public String featureSchemaVersion() {
        return featureSchemaVersion;
    }

    /**
     * Optional named {@link FeatureSchema} projection this artifact binds to.
     * When present, required feature names must match that projection's ordered list
     * exactly. When absent, features must still be canonical names for the schema.
     */
    public FeatureProjectionId requiredProjection() {
        return requiredProjection;
    }

    public List<String> requiredFeatureNames() {
        return requiredFeatureNames;
    }

    public int declaredFeatureDimension() {
        return declaredFeatureDimension;
    }

    public ScorerOutputRange outputRange() {
        return outputRange;
    }

    public ScorerArtifactCapabilities capabilities() {
        return capabilities;
    }

    /**
     * Deterministic provenance fingerprint over declared contract fields only.
     * Excludes timestamps, paths, hostnames, and random identifiers.
     */
    public String configurationFingerprintSha256Hex() {
        return ScorerArtifactFingerprints.sha256HexUtf8(canonicalConfigurationPayload());
    }

    String canonicalConfigurationPayload() {
        StringBuilder sb = new StringBuilder(512);
        appendField(sb, "descriptorSchemaVersion", Integer.toString(descriptorSchemaVersion));
        appendField(sb, "scorerId", scorerId);
        appendField(sb, "scorerVersion", scorerVersion);
        appendField(sb, "artifactId", artifactId);
        appendField(sb, "artifactFormat", artifactFormat);
        appendField(sb, "scorerType", scorerType);
        appendField(sb, "digestAlgorithm", artifactDigest.algorithm().wireName());
        appendField(sb, "digest", artifactDigest.digestHex());
        appendField(sb, "featureSchemaVersion", featureSchemaVersion);
        appendField(sb, "requiredProjection", requiredProjection == null ? "" : requiredProjection.name());
        appendList(sb, "requiredFeatures", requiredFeatureNames);
        appendField(sb, "featureDimension", Integer.toString(declaredFeatureDimension));
        appendField(sb, "outputMin", Double.toString(outputRange.minimum()));
        appendField(sb, "outputMax", Double.toString(outputRange.maximum()));
        appendField(sb, "supportsExplainability", Boolean.toString(capabilities.supportsExplainability()));
        appendField(
            sb,
            "supportsPerFeatureAttribution",
            Boolean.toString(capabilities.supportsPerFeatureAttribution())
        );
        appendField(
            sb,
            "claimsDeterministicExecution",
            Boolean.toString(capabilities.claimsDeterministicExecution())
        );
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String name, String value) {
        sb.append(name.length()).append(':').append(name)
            .append('=')
            .append(value.length()).append(':').append(value)
            .append('\n');
    }

    private static void appendList(StringBuilder sb, String name, List<String> values) {
        sb.append(name.length()).append(':').append(name)
            .append('=')
            .append(values.size());
        for (String value : values) {
            sb.append(':').append(value.length()).append(':').append(value);
        }
        sb.append('\n');
    }

    static String requireIdentityToken(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        String trimmed = value.trim();
        if (!ID_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(fieldName + " has invalid format");
        }
        return trimmed;
    }

    static String requireScorerType(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("scorerType must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!TYPE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("scorerType has invalid format");
        }
        return normalized;
    }

    static String requireArtifactFormat(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("artifactFormat must not be blank");
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (!TYPE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("artifactFormat has invalid format");
        }
        return trimmed;
    }

    static List<String> requireFeatureNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("requiredFeatureNames must not be empty");
        }
        if (names.size() > MAX_FEATURE_COUNT) {
            throw new IllegalArgumentException("requiredFeatureNames exceeds maximum size " + MAX_FEATURE_COUNT);
        }
        return List.copyOf(names);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ScorerArtifactDescriptor that)) {
            return false;
        }
        return descriptorSchemaVersion == that.descriptorSchemaVersion
            && declaredFeatureDimension == that.declaredFeatureDimension
            && Objects.equals(scorerId, that.scorerId)
            && Objects.equals(scorerVersion, that.scorerVersion)
            && Objects.equals(artifactId, that.artifactId)
            && Objects.equals(artifactFormat, that.artifactFormat)
            && Objects.equals(scorerType, that.scorerType)
            && Objects.equals(artifactDigest, that.artifactDigest)
            && Objects.equals(featureSchemaVersion, that.featureSchemaVersion)
            && Objects.equals(requiredProjection, that.requiredProjection)
            && Objects.equals(requiredFeatureNames, that.requiredFeatureNames)
            && Objects.equals(outputRange, that.outputRange)
            && Objects.equals(capabilities, that.capabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            descriptorSchemaVersion,
            scorerId,
            scorerVersion,
            artifactId,
            artifactFormat,
            scorerType,
            artifactDigest,
            featureSchemaVersion,
            requiredProjection,
            requiredFeatureNames,
            declaredFeatureDimension,
            outputRange,
            capabilities
        );
    }

    public static final class Builder {
        private int descriptorSchemaVersion = DESCRIPTOR_SCHEMA_VERSION;
        private String scorerId;
        private String scorerVersion;
        private String artifactId;
        private String artifactFormat;
        private String scorerType;
        private ArtifactDigest artifactDigest;
        private String featureSchemaVersion;
        private FeatureProjectionId requiredProjection;
        private List<String> requiredFeatureNames = List.of();
        private Integer declaredFeatureDimension;
        private ScorerOutputRange outputRange = ScorerOutputRange.unitInterval();
        private ScorerArtifactCapabilities capabilities = ScorerArtifactCapabilities.none();

        private Builder() {
        }

        public Builder descriptorSchemaVersion(int descriptorSchemaVersion) {
            this.descriptorSchemaVersion = descriptorSchemaVersion;
            return this;
        }

        public Builder scorerId(String scorerId) {
            this.scorerId = scorerId;
            return this;
        }

        public Builder scorerVersion(String scorerVersion) {
            this.scorerVersion = scorerVersion;
            return this;
        }

        public Builder artifactId(String artifactId) {
            this.artifactId = artifactId;
            return this;
        }

        public Builder artifactFormat(String artifactFormat) {
            this.artifactFormat = artifactFormat;
            return this;
        }

        public Builder scorerType(String scorerType) {
            this.scorerType = scorerType;
            return this;
        }

        public Builder artifactDigest(ArtifactDigest artifactDigest) {
            this.artifactDigest = artifactDigest;
            return this;
        }

        public Builder featureSchemaVersion(String featureSchemaVersion) {
            this.featureSchemaVersion = featureSchemaVersion;
            return this;
        }

        public Builder requiredProjection(FeatureProjectionId requiredProjection) {
            this.requiredProjection = requiredProjection;
            return this;
        }

        public Builder requiredFeatureNames(List<String> requiredFeatureNames) {
            this.requiredFeatureNames = requiredFeatureNames;
            return this;
        }

        public Builder declaredFeatureDimension(int declaredFeatureDimension) {
            this.declaredFeatureDimension = declaredFeatureDimension;
            return this;
        }

        public Builder outputRange(ScorerOutputRange outputRange) {
            this.outputRange = outputRange;
            return this;
        }

        public Builder capabilities(ScorerArtifactCapabilities capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public ScorerArtifactDescriptor build() {
            String id = requireIdentityToken(scorerId, "scorerId");
            String version = requireIdentityToken(scorerVersion, "scorerVersion");
            String artId = requireIdentityToken(artifactId, "artifactId");
            String format = requireArtifactFormat(artifactFormat);
            String type = requireScorerType(scorerType);
            Objects.requireNonNull(artifactDigest, "artifactDigest");
            if (featureSchemaVersion == null || featureSchemaVersion.isBlank()) {
                throw new IllegalArgumentException("featureSchemaVersion must not be blank");
            }
            List<String> features = requireFeatureNames(requiredFeatureNames);
            int dimension = declaredFeatureDimension == null ? features.size() : declaredFeatureDimension;
            Objects.requireNonNull(outputRange, "outputRange");
            Objects.requireNonNull(capabilities, "capabilities");
            this.scorerId = id;
            this.scorerVersion = version;
            this.artifactId = artId;
            this.artifactFormat = format;
            this.scorerType = type;
            this.featureSchemaVersion = featureSchemaVersion.trim();
            this.requiredFeatureNames = features;
            this.declaredFeatureDimension = dimension;
            return new ScorerArtifactDescriptor(this);
        }
    }
}
