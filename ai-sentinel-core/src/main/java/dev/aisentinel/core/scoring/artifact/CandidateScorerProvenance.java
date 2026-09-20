package dev.aisentinel.core.scoring.artifact;

import dev.aisentinel.core.model.FeatureProjectionId;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic loaded-candidate provenance derived from the validated descriptor
 * and integrity verification outcome.
 * <p>
 * {@code CONFIGURATION FINGERPRINT != ARTIFACT DIGEST}<br>
 * Runtime status is operational state and is not part of this identity payload.
 */
public final class CandidateScorerProvenance {

    private final String scorerId;
    private final String scorerVersion;
    private final String artifactId;
    private final String artifactFormat;
    private final String scorerType;
    private final ArtifactDigest declaredDigest;
    private final boolean artifactBytesVerified;
    private final String verifiedDigestHex;
    private final String featureSchemaVersion;
    private final FeatureProjectionId requiredProjection;
    private final List<String> requiredFeatureNames;
    private final int declaredFeatureDimension;
    private final String configurationFingerprintSha256Hex;
    private final String runtimeImplementationId;

    public CandidateScorerProvenance(
        ScorerArtifactDescriptor descriptor,
        boolean artifactBytesVerified,
        String verifiedDigestHex,
        String runtimeImplementationId
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        this.scorerId = descriptor.scorerId();
        this.scorerVersion = descriptor.scorerVersion();
        this.artifactId = descriptor.artifactId();
        this.artifactFormat = descriptor.artifactFormat();
        this.scorerType = descriptor.scorerType();
        this.declaredDigest = descriptor.artifactDigest();
        this.artifactBytesVerified = artifactBytesVerified;
        this.verifiedDigestHex = verifiedDigestHex == null ? "" : verifiedDigestHex;
        this.featureSchemaVersion = descriptor.featureSchemaVersion();
        this.requiredProjection = descriptor.requiredProjection();
        this.requiredFeatureNames = descriptor.requiredFeatureNames();
        this.declaredFeatureDimension = descriptor.declaredFeatureDimension();
        this.configurationFingerprintSha256Hex = descriptor.configurationFingerprintSha256Hex();
        this.runtimeImplementationId = runtimeImplementationId == null ? "" : runtimeImplementationId;
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

    public ArtifactDigest declaredDigest() {
        return declaredDigest;
    }

    public boolean artifactBytesVerified() {
        return artifactBytesVerified;
    }

    public String verifiedDigestHex() {
        return verifiedDigestHex;
    }

    public String featureSchemaVersion() {
        return featureSchemaVersion;
    }

    public FeatureProjectionId requiredProjection() {
        return requiredProjection;
    }

    public List<String> requiredFeatureNames() {
        return requiredFeatureNames;
    }

    public int declaredFeatureDimension() {
        return declaredFeatureDimension;
    }

    public String configurationFingerprintSha256Hex() {
        return configurationFingerprintSha256Hex;
    }

    /**
     * Explicit supported runtime implementation identity (for example
     * {@code isolation_forest_model_codec_v1}). Empty when no implementation was activated.
     */
    public String runtimeImplementationId() {
        return runtimeImplementationId;
    }
}
