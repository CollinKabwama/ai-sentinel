package dev.aisentinel.core.scoring.shadow;

/**
 * Why an enabled shadow attempt was {@link ShadowScoringStatus#NOT_ELIGIBLE}.
 */
public enum ShadowIneligibilityReason {

    /** Enabled configuration lacked an {@link AcceptedCandidateIdentity} binding. */
    MISSING_ACCEPTED_IDENTITY,

    /**
     * Loaded candidate provenance did not match the accepted identity
     * (scorer id/version, artifact id, verified digest, or configuration fingerprint).
     */
    IDENTITY_MISMATCH
}
