package dev.aisentinel.benchmark.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentBenchmarkResultWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writesJsonWithSummaryAndScenario() throws Exception {
        DeploymentBenchmarkResult result = new DeploymentBenchmarkResult(
            "1",
            "1",
            "ai-sentinel-deployment-benchmark",
            "deployment-benchmark",
            "2026-09-05T20:00:00Z",
            "0.3.0",
            "abc123",
            "JAVA_REMOTE_NORMAL",
            "remote-http-loopback",
            "local-memory",
            "statistical",
            "1",
            "21",
            null,
            null,
            "29.1.3",
            "Mac OS X",
            "aarch64",
            1,
            10,
            20,
            500L,
            "boundary",
            List.of("include"),
            List.of("exclude"),
            Map.of("attempts", 20, "successes", 20),
            new LatencyStats("milliseconds", "nearest-rank", 20, 1.0, 2.0, 3.0),
            new LatencyStats("milliseconds", "nearest-rank", 0, null, null, null),
            100.0,
            null,
            "notes");

        Path out = DeploymentBenchmarkResultWriter.write(
            tempDir, "smoke", List.of(result), Map.of("dockerAvailable", true));

        String json = Files.readString(out);
        assertThat(json).contains("\"scenario\" : \"JAVA_REMOTE_NORMAL\"");
        assertThat(json).contains("\"dockerAvailable\" : true");
        assertThat(json).contains("\"summary\"");
    }
}
