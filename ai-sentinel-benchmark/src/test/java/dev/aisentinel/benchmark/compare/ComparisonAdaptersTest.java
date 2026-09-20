package dev.aisentinel.benchmark.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonAdaptersTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsReferenceBaselineFixture() throws Exception {
        Path baseline = fixture("reference-baseline-fixture.json");
        NormalizedBenchmarkSet set = ComparisonAdapters.loadReferenceBaseline(baseline);

        assertThat(set.family()).isEqualTo(ComparisonFamily.JMH);
        assertThat(set.profile()).isEqualTo("reference");
        assertThat(set.metrics()).extracting(NormalizedMetric::metric).contains("p50", "mean");
    }

    @Test
    void loadsRawJmhCandidateWithSiblingManifest() throws Exception {
        Path candidate = tempDir.resolve("jmh-pass.json");
        Path manifest = tempDir.resolve("manifest-pass.json");
        Files.copy(fixture("jmh-pass-fixture.json"), candidate);
        Files.copy(fixture("manifest-reference-fixture.json"), manifest);

        NormalizedBenchmarkSet set = ComparisonAdapters.loadRawJmhCandidate(candidate);

        assertThat(set.profile()).isEqualTo("reference");
        assertThat(set.commit()).isEqualTo("candidate-commit");
        assertThat(set.dirtyTree()).isNull();
        assertThat(set.environment().os()).isEqualTo("Mac OS X");
    }

    @Test
    void loadsRawJmhCandidateDirtyTreeFromSiblingManifest() throws Exception {
        Path candidate = tempDir.resolve("jmh-dirty.json");
        Path manifest = tempDir.resolve("manifest-dirty.json");
        Files.copy(fixture("jmh-pass-fixture.json"), candidate);
        Files.writeString(manifest, Files.readString(fixture("manifest-reference-fixture.json"))
            .replace("\"jmhArgs\": \"-f 2 -wi 5 -i 5 -w 1s -r 1s\"", """
                "jmhArgs": "-f 2 -wi 5 -i 5 -w 1s -r 1s",
                    "dirtyTree": "true"
                """));

        NormalizedBenchmarkSet set = ComparisonAdapters.loadRawJmhCandidate(candidate);

        assertThat(set.dirtyTree()).isTrue();
    }

    @Test
    void loadsDeploymentAndResourceFixtures() throws Exception {
        NormalizedBenchmarkSet deployment = ComparisonAdapters.loadDeployment(fixture("deployment-baseline-fixture.json"));
        NormalizedBenchmarkSet resources = ComparisonAdapters.loadResources(fixture("resource-baseline-fixture.json"));

        assertThat(deployment.family()).isEqualTo(ComparisonFamily.DEPLOYMENT);
        assertThat(resources.family()).isEqualTo(ComparisonFamily.RESOURCES);
        assertThat(resources.metrics()).extracting(NormalizedMetric::metric).contains("processCpuCoresEquivalent");
    }

    private Path fixture(String name) {
        return Path.of("src/test/resources/benchmark-comparison/" + name);
    }
}
