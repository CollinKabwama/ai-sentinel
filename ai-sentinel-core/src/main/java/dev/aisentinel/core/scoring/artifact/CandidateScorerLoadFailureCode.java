package dev.aisentinel.core.scoring.artifact;

/**
 * Structured failure categories for candidate scorer loading and readiness.
 * <p>
 * These are engineering/configuration outcomes.
 * {@code ARTIFACT INTEGRITY FAILURE != ATTACK}<br>
 * {@code MODEL FAILURE != ATTACK}<br>
 * {@code INFRASTRUCTURE FAILURE != ATTACK}
 */
public enum CandidateScorerLoadFailureCode {

    /** No candidate descriptor or configuration was provided. */
    NOT_CONFIGURED,

    /** Descriptor failed {@link ScorerArtifactValidator} checks. */
    DESCRIPTOR_REJECTED,

    /** Artifact bytes were not supplied. */
    ARTIFACT_UNAVAILABLE,

    /** Artifact bytes were present but empty where a payload is required. */
    ARTIFACT_EMPTY,

    /** Artifact exceeds the supported maximum size for candidate loading. */
    ARTIFACT_TOO_LARGE,

    /** Declared digest metadata algorithm is not supported for byte verification. */
    UNSUPPORTED_DIGEST_ALGORITHM,

    /** Computed SHA-256 of artifact bytes does not match the declared digest. */
    DIGEST_MISMATCH,

    /**
     * Descriptor scorer type is recognized but no candidate runtime loader
     * implementation exists yet for that type.
     */
    UNSUPPORTED_RUNTIME_IMPLEMENTATION,

    /** Descriptor scorer type is supported, but the artifact format is not loadable by this runtime. */
    UNSUPPORTED_ARTIFACT_FORMAT,

    /** Artifact bytes could not be decoded as the claimed supported format. */
    ARTIFACT_DECODE_FAILED,

    /** Decoded model feature dimension does not match the validated descriptor. */
    FEATURE_DIMENSION_MISMATCH,

    /** Scorer/model construction failed after verification (contained). */
    CONSTRUCTION_FAILURE
}
