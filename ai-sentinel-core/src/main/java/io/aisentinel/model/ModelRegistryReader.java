package io.aisentinel.model;

import java.util.Optional;

/**
 * Read-only registry view for serving nodes: resolve which artifact is active and fetch bytes.
 * Implementations must be fail-safe for the caller (no exceptions on missing files).
 */
public interface ModelRegistryReader {

    /**
     * Latest active metadata pointer for the tenant (e.g. from {@code active.json}).
     */
    Optional<ModelArtifactMetadata> resolveActiveMetadata(String tenantId);

    /**
     * Serialized model payload for a published version (e.g. binary IF codec bytes).
     */
    Optional<byte[]> fetchPayload(String tenantId, String modelVersion);
}
