package io.aisentinel.autoconfigure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.distributed.training.TrainingFingerprintHashes;
import io.aisentinel.model.ModelArtifactMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FilesystemModelRegistryTest {

    @Test
    void resolvesActiveAndPayload(@TempDir Path root) throws Exception {
        String tenant = "default";
        byte[] payload = new byte[] {7, 8, 9};
        String hash = TrainingFingerprintHashes.sha256HexBytes(payload);
        var meta = new ModelArtifactMetadata(
            tenant,
            "v-test",
            ModelArtifactMetadata.CURRENT_ARTIFACT_SCHEMA_VERSION,
            2,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1,
            1000L,
            5,
            10,
            4,
            3,
            hash
        );
        Path artifacts = root.resolve(tenant).resolve("artifacts");
        Files.createDirectories(artifacts);
        ObjectMapper om = new ObjectMapper();
        Map<String, Object> metaMap = new LinkedHashMap<>();
        metaMap.put("tenantId", meta.tenantId());
        metaMap.put("modelVersion", meta.modelVersion());
        metaMap.put("artifactSchemaVersion", meta.artifactSchemaVersion());
        metaMap.put("trainingCandidateSchemaVersion", meta.trainingCandidateSchemaVersion());
        metaMap.put("modelType", meta.modelType());
        metaMap.put("trainedAtEpochMillis", meta.trainedAtEpochMillis());
        metaMap.put("featureDimension", meta.featureDimension());
        metaMap.put("numTrees", meta.numTrees());
        metaMap.put("maxDepth", meta.maxDepth());
        metaMap.put("trainingSampleCount", meta.trainingSampleCount());
        metaMap.put("payloadSha256Hex", meta.payloadSha256Hex());
        om.writeValue(artifacts.resolve("v-test.meta.json").toFile(), metaMap);
        Files.write(artifacts.resolve("v-test.payload.bin"), payload);
        Map<String, String> active = Map.of("modelVersion", "v-test");
        om.writeValue(root.resolve(tenant).resolve("active.json").toFile(), active);

        FilesystemModelRegistry reg = new FilesystemModelRegistry(root, om);
        assertThat(reg.resolveActiveMetadata(tenant)).isPresent();
        assertThat(reg.fetchPayload(tenant, "v-test")).contains(payload);
    }
}
