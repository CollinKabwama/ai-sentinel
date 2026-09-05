package dev.aisentinel.benchmark.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceBenchmarkResultWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesJsonWithResourceMetricsAndSummary() throws Exception {
        ResourceBenchmarkResult result = new ResourceBenchmarkResult(
            "1",
            "1",
            "0.3.0",
            "abc123",
            false,
            "2026-09-05T20:00:00Z",
            "RESOURCE_IN_PROCESS",
            "in-process",
            "local-memory",
            "statistical",
            4,
            1000L,
            2000L,
            100L,
            100L,
            0L,
            50.0,
            "21",
            "Mac OS X 15.0",
            "aarch64",
            10,
            1L,
            2L,
            3L,
            4L,
            5L,
            6L,
            7L,
            0.7,
            7.0,
            8L,
            9L,
            10L,
            11L,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "boundary",
            "limitations");

        Path out = ResourceBenchmarkResultWriter.write(
            tempDir, "smoke", List.of(result), Map.of("samplingIntervalMillis", 250));

        String json = Files.readString(out);
        assertThat(json).contains("\"scenario\" : \"RESOURCE_IN_PROCESS\"");
        assertThat(json).contains("\"processCpuCoresEquivalent\" : 0.7");
        assertThat(json).contains("\"samplingIntervalMillis\" : 250");
    }
}
