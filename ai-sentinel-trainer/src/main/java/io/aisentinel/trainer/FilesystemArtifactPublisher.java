package io.aisentinel.trainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.distributed.training.TrainingFingerprintHashes;
import io.aisentinel.model.ModelArtifactMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the same layout {@link io.aisentinel.autoconfigure.model.FilesystemModelRegistry} reads.
 */
public final class FilesystemArtifactPublisher {

    private final ObjectMapper mapper;

    public FilesystemArtifactPublisher(ObjectMapper mapper) {
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    public void publish(String tenantId, ModelArtifactMetadata metadata, byte[] payload, Path registryRoot) throws IOException {
        String t = tenantId != null && !tenantId.isBlank() ? tenantId : "default";
        Path base = registryRoot.resolve(t).toAbsolutePath().normalize();
        Path artifacts = base.resolve("artifacts");
        Files.createDirectories(artifacts);
        String v = metadata.modelVersion();
        Path metaTmp = artifacts.resolve(v + ".meta.json.tmp");
        Path metaFinal = artifacts.resolve(v + ".meta.json");
        Path payTmp = artifacts.resolve(v + ".payload.bin.tmp");
        Path payFinal = artifacts.resolve(v + ".payload.bin");
        mapper.writerWithDefaultPrettyPrinter().writeValue(metaTmp.toFile(), toMetaMap(metadata));
        Files.move(metaTmp, metaFinal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.write(payTmp, payload);
        Files.move(payTmp, payFinal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Path activeTmp = base.resolve("active.json.tmp");
        Path activeFinal = base.resolve("active.json");
        Map<String, String> active = new LinkedHashMap<>();
        active.put("modelVersion", v);
        mapper.writeValue(activeTmp.toFile(), active);
        Files.move(activeTmp, activeFinal, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Map<String, Object> toMetaMap(ModelArtifactMetadata m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tenantId", m.tenantId());
        map.put("modelVersion", m.modelVersion());
        map.put("artifactSchemaVersion", m.artifactSchemaVersion());
        map.put("trainingCandidateSchemaVersion", m.trainingCandidateSchemaVersion());
        map.put("modelType", m.modelType());
        map.put("trainedAtEpochMillis", m.trainedAtEpochMillis());
        map.put("featureDimension", m.featureDimension());
        map.put("numTrees", m.numTrees());
        map.put("maxDepth", m.maxDepth());
        map.put("trainingSampleCount", m.trainingSampleCount());
        map.put("payloadSha256Hex", m.payloadSha256Hex());
        return map;
    }

    public static String newVersionId() {
        return "m-" + System.currentTimeMillis();
    }

    public static ModelArtifactMetadata buildMetadata(
        String tenantId,
        String modelVersion,
        int trainingCandidateSchemaVersion,
        long trainedAt,
        int featureDimension,
        int numTrees,
        int maxDepth,
        int trainingSampleCount,
        byte[] payload
    ) {
        String hash = TrainingFingerprintHashes.sha256HexBytes(payload);
        return new ModelArtifactMetadata(
            tenantId,
            modelVersion,
            ModelArtifactMetadata.CURRENT_ARTIFACT_SCHEMA_VERSION,
            trainingCandidateSchemaVersion,
            ModelArtifactMetadata.MODEL_TYPE_ISOLATION_FOREST_V1,
            trainedAt,
            featureDimension,
            numTrees,
            maxDepth,
            trainingSampleCount,
            hash
        );
    }
}
