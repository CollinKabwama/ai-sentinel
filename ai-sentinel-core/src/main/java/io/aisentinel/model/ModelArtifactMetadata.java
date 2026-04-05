package io.aisentinel.model;

import io.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.util.Objects;

/**
 * Metadata for a versioned Isolation Forest artifact published to a model registry.
 * Payload bytes are stored separately; {@link #payloadSha256Hex()} must match the payload.
 */
public final class ModelArtifactMetadata {

    public static final String MODEL_TYPE_ISOLATION_FOREST_V1 = "isolation_forest_v1";
    public static final int CURRENT_ARTIFACT_SCHEMA_VERSION = 1;

    private final String tenantId;
    private final String modelVersion;
    private final int artifactSchemaVersion;
    private final int trainingCandidateSchemaVersion;
    private final String modelType;
    private final long trainedAtEpochMillis;
    private final int featureDimension;
    private final int numTrees;
    private final int maxDepth;
    private final int trainingSampleCount;
    private final String payloadSha256Hex;

    public ModelArtifactMetadata(
        String tenantId,
        String modelVersion,
        int artifactSchemaVersion,
        int trainingCandidateSchemaVersion,
        String modelType,
        long trainedAtEpochMillis,
        int featureDimension,
        int numTrees,
        int maxDepth,
        int trainingSampleCount,
        String payloadSha256Hex
    ) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.modelVersion = modelVersion != null ? modelVersion : "";
        this.artifactSchemaVersion = artifactSchemaVersion;
        this.trainingCandidateSchemaVersion = trainingCandidateSchemaVersion;
        this.modelType = modelType != null ? modelType : "";
        this.trainedAtEpochMillis = trainedAtEpochMillis;
        this.featureDimension = featureDimension;
        this.numTrees = numTrees;
        this.maxDepth = maxDepth;
        this.trainingSampleCount = trainingSampleCount;
        this.payloadSha256Hex = payloadSha256Hex != null ? payloadSha256Hex.toLowerCase() : "";
    }

    public String tenantId() {
        return tenantId;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public int artifactSchemaVersion() {
        return artifactSchemaVersion;
    }

    public int trainingCandidateSchemaVersion() {
        return trainingCandidateSchemaVersion;
    }

    public String modelType() {
        return modelType;
    }

    public long trainedAtEpochMillis() {
        return trainedAtEpochMillis;
    }

    public int featureDimension() {
        return featureDimension;
    }

    public int numTrees() {
        return numTrees;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int trainingSampleCount() {
        return trainingSampleCount;
    }

    public String payloadSha256Hex() {
        return payloadSha256Hex;
    }

    public boolean isValidIsolationForestV1Pointer() {
        return !modelVersion.isBlank()
            && artifactSchemaVersion == CURRENT_ARTIFACT_SCHEMA_VERSION
            && MODEL_TYPE_ISOLATION_FOREST_V1.equals(modelType)
            && featureDimension > 0
            && numTrees > 0
            && maxDepth > 0
            && payloadSha256Hex.length() == 64;
    }

    public boolean payloadMatches(byte[] payload) {
        if (payload == null) {
            return false;
        }
        return payloadSha256Hex.equalsIgnoreCase(TrainingFingerprintHashes.sha256HexBytes(payload));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelArtifactMetadata that)) return false;
        return artifactSchemaVersion == that.artifactSchemaVersion
            && trainingCandidateSchemaVersion == that.trainingCandidateSchemaVersion
            && trainedAtEpochMillis == that.trainedAtEpochMillis
            && featureDimension == that.featureDimension
            && numTrees == that.numTrees
            && maxDepth == that.maxDepth
            && trainingSampleCount == that.trainingSampleCount
            && Objects.equals(tenantId, that.tenantId)
            && Objects.equals(modelVersion, that.modelVersion)
            && Objects.equals(modelType, that.modelType)
            && Objects.equals(payloadSha256Hex, that.payloadSha256Hex);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, modelVersion, artifactSchemaVersion, trainingCandidateSchemaVersion,
            modelType, trainedAtEpochMillis, featureDimension, numTrees, maxDepth, trainingSampleCount,
            payloadSha256Hex);
    }
}
