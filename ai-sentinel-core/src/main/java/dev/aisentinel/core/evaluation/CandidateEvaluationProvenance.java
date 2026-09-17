package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.model.FeatureProjectionId;
import dev.aisentinel.core.scoring.artifact.CandidateScorerProvenance;

import java.util.List;
import java.util.Objects;

/**
 * Immutable evidence snapshot of loaded-candidate provenance.
 * <p>
 * {@code CONFIGURATION FINGERPRINT != ARTIFACT DIGEST}
 */
public record CandidateEvaluationProvenance(
    String scorerId,
    String scorerVersion,
    String artifactId,
    String artifactFormat,
    String scorerType,
    String artifactDigestAlgorithm,
    String artifactDigestHex,
    boolean artifactBytesVerified,
    String verifiedDigestHex,
    String featureSchemaVersion,
    String requiredProjection,
    List<String> requiredFeatureNames,
    int declaredFeatureDimension,
    String configurationFingerprintSha256Hex,
    String runtimeImplementationId
) {
    public CandidateEvaluationProvenance {
        scorerId = nullToEmpty(scorerId);
        scorerVersion = nullToEmpty(scorerVersion);
        artifactId = nullToEmpty(artifactId);
        artifactFormat = nullToEmpty(artifactFormat);
        scorerType = nullToEmpty(scorerType);
        artifactDigestAlgorithm = nullToEmpty(artifactDigestAlgorithm);
        artifactDigestHex = nullToEmpty(artifactDigestHex);
        verifiedDigestHex = nullToEmpty(verifiedDigestHex);
        featureSchemaVersion = nullToEmpty(featureSchemaVersion);
        requiredProjection = nullToEmpty(requiredProjection);
        requiredFeatureNames = requiredFeatureNames == null ? List.of() : List.copyOf(requiredFeatureNames);
        configurationFingerprintSha256Hex = nullToEmpty(configurationFingerprintSha256Hex);
        runtimeImplementationId = nullToEmpty(runtimeImplementationId);
        if (declaredFeatureDimension < 0) {
            throw new IllegalArgumentException("declaredFeatureDimension must be >= 0");
        }
    }

    static CandidateEvaluationProvenance from(CandidateScorerProvenance provenance) {
        CandidateScorerProvenance safe = Objects.requireNonNull(provenance, "provenance");
        FeatureProjectionId projection = safe.requiredProjection();
        return new CandidateEvaluationProvenance(
            safe.scorerId(),
            safe.scorerVersion(),
            safe.artifactId(),
            safe.artifactFormat(),
            safe.scorerType(),
            safe.declaredDigest().algorithm().wireName(),
            safe.declaredDigest().digestHex(),
            safe.artifactBytesVerified(),
            safe.verifiedDigestHex(),
            safe.featureSchemaVersion(),
            projection == null ? "" : projection.name(),
            safe.requiredFeatureNames(),
            safe.declaredFeatureDimension(),
            safe.configurationFingerprintSha256Hex(),
            safe.runtimeImplementationId()
        );
    }

    static CandidateEvaluationProvenance absent() {
        return new CandidateEvaluationProvenance(
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            false,
            "",
            "",
            "",
            List.of(),
            0,
            "",
            ""
        );
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
