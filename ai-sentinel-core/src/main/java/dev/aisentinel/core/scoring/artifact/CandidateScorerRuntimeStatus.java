package dev.aisentinel.core.scoring.artifact;

/**
 * Operational readiness/health of a candidate scorer after loading attempts.
 * <p>
 * This answers engineering availability questions only. It does not answer
 * detection quality, promotion readiness, or shadow eligibility.
 * <p>
 * {@code SCORER HEALTH != DETECTION QUALITY}<br>
 * {@code READY != APPROVED}<br>
 * {@code READY != SHADOW ENABLED}<br>
 * {@code READY != PRODUCTION READY}
 */
public enum CandidateScorerRuntimeStatus {

    /**
     * No candidate was supplied/configured. Default AI-Sentinel behavior is unchanged.
     */
    NOT_CONFIGURED,

    /**
     * Descriptor, artifact bytes, digest, format, schema, or construction cannot be accepted.
     */
    INVALID,

    /**
     * A candidate was expected, but a supported runtime implementation is not available
     * for this scorer type, or required artifact bytes were not supplied.
     */
    UNAVAILABLE,

    /**
     * Candidate passed required integrity, compatibility, and construction checks and
     * can be invoked by a future authorized consumer. This does not imply accuracy,
     * approval, shadow enablement, or production authority.
     */
    READY
}
