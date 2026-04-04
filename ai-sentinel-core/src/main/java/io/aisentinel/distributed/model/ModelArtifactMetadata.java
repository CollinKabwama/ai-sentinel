package io.aisentinel.distributed.model;

/**
 * Describes a trained Isolation Forest artifact in a registry or blob store.
 */
public record ModelArtifactMetadata(
    String versionId,
    String sha256,
    String uri,
    long createdAtEpochMillis,
    int schemaVersion
) {
}
