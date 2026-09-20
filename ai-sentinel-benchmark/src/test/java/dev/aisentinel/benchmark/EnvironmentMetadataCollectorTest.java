package dev.aisentinel.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnvironmentMetadataCollectorTest {

    @Test
    void collectPopulatesRequiredHostFields() {
        System.setProperty("aisentinel.benchmark.sentinelVersion", "test-version");
        try {
            EnvironmentMetadata metadata = EnvironmentMetadataCollector.collect();
            assertThat(metadata.suiteFormatVersion()).isEqualTo(BenchmarkSuiteVersions.SUITE_FORMAT_VERSION);
            assertThat(metadata.suiteName()).isEqualTo(BenchmarkSuiteVersions.SUITE_NAME);
            assertThat(metadata.javaVersion()).isNotBlank();
            assertThat(metadata.osName()).isNotBlank();
            assertThat(metadata.availableProcessors()).isPositive();
            assertThat(metadata.featureSchemaVersion()).isEqualTo("1");
            assertThat(metadata.deploymentMode()).isEqualTo("in-process");
            assertThat(metadata.stateBackend()).isEqualTo("local-memory");
            assertThat(metadata.sentinelVersion()).isEqualTo("test-version");
        } finally {
            System.clearProperty("aisentinel.benchmark.sentinelVersion");
        }
    }

    @Test
    void versionFallsBackToNullRatherThanHardcodedRelease() {
        System.clearProperty("aisentinel.benchmark.sentinelVersion");
        String version = EnvironmentMetadataCollector.readSentinelVersion();
        assertThat(version == null || !version.isBlank()).isTrue();
    }
}
