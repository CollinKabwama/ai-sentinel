package dev.aisentinel.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkManifestWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesManifestWithNullSafeFieldsAndExtras() throws Exception {
        EnvironmentMetadata metadata = EnvironmentMetadata.builder()
            .sentinelVersion("0.3.0")
            .gitCommit(null)
            .capturedAtUtc("2026-09-04T00:00:00Z")
            .javaVersion("21")
            .featureSchemaVersion("1")
            .build();
        Path out = tempDir.resolve("manifest.json");
        BenchmarkManifestWriter.write(out, metadata, Map.of("mode", "smoke"));

        String json = Files.readString(out);
        assertThat(json).contains("\"sentinelVersion\": \"0.3.0\"");
        assertThat(json).contains("\"commit\": null");
        assertThat(json).contains("\"mode\": \"smoke\"");
        assertThat(json).contains("Not a production SLA");
    }

    @Test
    void quoteEscapesControlCharacters() {
        assertThat(BenchmarkManifestWriter.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
    }
}
