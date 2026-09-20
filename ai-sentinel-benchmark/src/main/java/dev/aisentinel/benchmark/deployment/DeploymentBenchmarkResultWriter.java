package dev.aisentinel.benchmark.deployment;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class DeploymentBenchmarkResultWriter {

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private DeploymentBenchmarkResultWriter() {
    }

    static Path write(Path outputDir, String mode, List<DeploymentBenchmarkResult> results,
                      Map<String, Object> summary) throws IOException {
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve("deployment-" + mode + ".json");
        JSON.writeValue(out.toFile(), Map.of(
            "resultSchemaVersion", DeploymentBenchmarkVersions.SCHEMA_VERSION,
            "harnessVersion", DeploymentBenchmarkVersions.HARNESS_VERSION,
            "suiteName", DeploymentBenchmarkVersions.SUITE_NAME,
            "results", results,
            "summary", summary));
        return out;
    }
}
