package io.aisentinel.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelArtifactMetadataTest {

    @Test
    void payloadMatchesSha256() {
        byte[] p = new byte[] {1, 2, 3};
        var m = new ModelArtifactMetadata(
            "t",
            "v1",
            ModelArtifactMetadata.CURRENT_ARTIFACT_SCHEMA_VERSION,
            2,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1,
            100L,
            5,
            10,
            4,
            3,
            "deadbeef"
        );
        assertThat(m.isValidIsolationForestV1Pointer()).isFalse();
        String hex = io.aisentinel.distributed.training.TrainingFingerprintHashes.sha256HexBytes(p);
        var ok = new ModelArtifactMetadata(
            "t",
            "v1",
            ModelArtifactMetadata.CURRENT_ARTIFACT_SCHEMA_VERSION,
            2,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1,
            100L,
            5,
            10,
            4,
            3,
            hex
        );
        assertThat(ok.isValidIsolationForestV1Pointer()).isTrue();
        assertThat(ok.payloadMatches(p)).isTrue();
    }
}
