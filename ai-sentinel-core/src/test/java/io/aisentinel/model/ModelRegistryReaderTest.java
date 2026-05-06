package io.aisentinel.model;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ModelRegistryReaderTest {

    @Test
    void emptyReaderContract() {
        ModelRegistryReader reader = new ModelRegistryReader() {
            @Override
            public Optional<ModelArtifactMetadata> resolveActiveMetadata(String tenantId) {
                return Optional.empty();
            }

            @Override
            public Optional<byte[]> fetchPayload(String tenantId, String modelVersion) {
                return Optional.empty();
            }
        };

        assertThat(reader.resolveActiveMetadata("t")).isEmpty();
        assertThat(reader.fetchPayload("t", "v1")).isEmpty();
    }

    @Test
    void readerCanReturnMetadataAndBytes() {
        ModelArtifactMetadata meta = new ModelArtifactMetadata(
            "tenant-a",
            "v9",
            ModelArtifactMetadata.CURRENT_ARTIFACT_SCHEMA_VERSION,
            1,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1,
            1_700_000_000_000L,
            12,
            256,
            12,
            10_000,
            "ab".repeat(32)
        );
        byte[] bytes = new byte[] {1, 2, 3};

        ModelRegistryReader reader = new ModelRegistryReader() {
            @Override
            public Optional<ModelArtifactMetadata> resolveActiveMetadata(String tenantId) {
                return "tenant-a".equals(tenantId) ? Optional.of(meta) : Optional.empty();
            }

            @Override
            public Optional<byte[]> fetchPayload(String tenantId, String modelVersion) {
                return "tenant-a".equals(tenantId) && "v9".equals(modelVersion)
                    ? Optional.of(bytes)
                    : Optional.empty();
            }
        };

        assertThat(reader.resolveActiveMetadata("tenant-a")).contains(meta);
        assertThat(reader.resolveActiveMetadata("other")).isEmpty();
        assertThat(reader.fetchPayload("tenant-a", "v9")).contains(bytes);
        assertThat(reader.fetchPayload("tenant-a", "v0")).isEmpty();
    }
}
