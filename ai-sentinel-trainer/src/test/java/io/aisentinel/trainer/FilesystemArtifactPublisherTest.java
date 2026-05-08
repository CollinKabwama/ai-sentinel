package io.aisentinel.trainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.model.ModelArtifactMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemArtifactPublisherTest {

    @Test
    void publishWritesActivePointerAndPayload(@TempDir Path registryRoot) throws Exception {
        FilesystemArtifactPublisher publisher = new FilesystemArtifactPublisher(new ObjectMapper());
        byte[] payload = new byte[] { 1, 2, 3, 4 };
        ModelArtifactMetadata meta = FilesystemArtifactPublisher.buildMetadata(
            "acme",
            "v-test-1",
            1,
            1_700_000_000_000L,
            5,
            3,
            2,
            10,
            payload);

        publisher.publish("acme", meta, payload, registryRoot);

        Path tenant = registryRoot.resolve("acme");
        assertThat(tenant.resolve("active.json")).exists();
        assertThat(tenant.resolve("artifacts").resolve("v-test-1.payload.bin")).exists();
        assertThat(tenant.resolve("artifacts").resolve("v-test-1.meta.json")).exists();
    }

    @Test
    void newVersionIdStartsWithPrefix() {
        assertThat(FilesystemArtifactPublisher.newVersionId()).startsWith("m-");
    }
}
