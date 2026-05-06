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
}
