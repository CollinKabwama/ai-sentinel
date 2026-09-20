package dev.aisentinel.core.scoring.lifecycle;

/**
 * How a {@link ModelLifecycleIdentity} was established.
 * <p>
 * {@code ARTIFACT_BACKED} identities bind verified candidate artifact digests.
 * {@code DESIGNATED_REFERENCE} identities allow a non-artifact champion label
 * (for example an in-process statistical scorer) without fabricating artifact
 * metadata.
 */
public enum ModelLifecycleIdentityKind {
    ARTIFACT_BACKED,
    DESIGNATED_REFERENCE
}
