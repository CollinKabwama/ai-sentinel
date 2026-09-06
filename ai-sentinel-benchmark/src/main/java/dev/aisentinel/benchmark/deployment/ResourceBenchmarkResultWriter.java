package dev.aisentinel.benchmark.deployment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class ResourceBenchmarkResultWriter {

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private ResourceBenchmarkResultWriter() {
    }

    static Path write(Path outputDir, String mode, List<ResourceBenchmarkResult> results,
                      Map<String, Object> summary) throws IOException {
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve("resource-" + mode + ".json");
        JSON.writeValue(out.toFile(), Map.of(
            "schemaVersion", ResourceBenchmarkVersions.SCHEMA_VERSION,
            "harnessVersion", ResourceBenchmarkVersions.HARNESS_VERSION,
            "results", results,
            "summary", summary));
        return out;
    }
}
