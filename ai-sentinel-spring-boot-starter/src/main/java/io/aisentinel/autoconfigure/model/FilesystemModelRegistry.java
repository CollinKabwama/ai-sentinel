package io.aisentinel.autoconfigure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.model.ModelArtifactMetadata;
import io.aisentinel.model.ModelRegistryReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Filesystem layout (written by {@code ai-sentinel-trainer}):
 * <pre>
 *   {root}/{tenantId}/active.json              → { "modelVersion": "..." }
 *   {root}/{tenantId}/artifacts/{version}.meta.json
 *   {root}/{tenantId}/artifacts/{version}.payload.bin
 * </pre>
 */
public final class FilesystemModelRegistry implements ModelRegistryReader {

    private static final Logger log = LoggerFactory.getLogger(FilesystemModelRegistry.class);

    private final Path root;
    private final ObjectMapper objectMapper;

    public FilesystemModelRegistry(Path root, ObjectMapper objectMapper) {
        this.root = root.toAbsolutePath().normalize();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public Optional<ModelArtifactMetadata> resolveActiveMetadata(String tenantId) {
        String t = tenantId != null && !tenantId.isBlank() ? tenantId : "default";
        Path activeFile = root.resolve(t).resolve("active.json");
        if (!Files.isRegularFile(activeFile)) {
            return Optional.empty();
        }
        try {
            JsonNode rootNode = objectMapper.readTree(activeFile.toFile());
            String modelVersion = text(rootNode, "modelVersion");
            if (modelVersion.isBlank()) {
                return Optional.empty();
            }
            Path metaFile = root.resolve(t).resolve("artifacts").resolve(modelVersion + ".meta.json");
            if (!Files.isRegularFile(metaFile)) {
                log.debug("Missing meta for active modelVersion={} tenant={}", modelVersion, t);
                return Optional.empty();
            }
            JsonNode n = objectMapper.readTree(metaFile.toFile());
            return Optional.of(parseMetadata(n));
        } catch (IOException e) {
            log.debug("Failed to read registry pointer for tenant {}: {}", t, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Optional<byte[]> fetchPayload(String tenantId, String modelVersion) {
        if (modelVersion == null || modelVersion.isBlank()) {
            return Optional.empty();
        }
        String t = tenantId != null && !tenantId.isBlank() ? tenantId : "default";
        Path bin = root.resolve(t).resolve("artifacts").resolve(modelVersion + ".payload.bin");
        if (!Files.isRegularFile(bin)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(bin);
            return Optional.of(bytes);
        } catch (IOException e) {
            log.debug("Failed to read payload {}: {}", bin, e.toString());
            return Optional.empty();
        }
    }

    private static ModelArtifactMetadata parseMetadata(JsonNode n) {
        return new ModelArtifactMetadata(
            text(n, "tenantId"),
            text(n, "modelVersion"),
            intOrZero(n, "artifactSchemaVersion"),
            intOrZero(n, "trainingCandidateSchemaVersion"),
            text(n, "modelType"),
            longOrZero(n, "trainedAtEpochMillis"),
            intOrZero(n, "featureDimension"),
            intOrZero(n, "numTrees"),
            intOrZero(n, "maxDepth"),
            intOrZero(n, "trainingSampleCount"),
            text(n, "payloadSha256Hex")
        );
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return "";
        }
        return n.get(field).asText("");
    }

    private static int intOrZero(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return 0;
        }
        return n.get(field).asInt(0);
    }

    private static long longOrZero(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return 0L;
        }
        return n.get(field).asLong(0L);
    }
}
