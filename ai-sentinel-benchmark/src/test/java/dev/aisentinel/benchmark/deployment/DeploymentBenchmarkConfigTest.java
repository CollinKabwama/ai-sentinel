package dev.aisentinel.benchmark.deployment;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeploymentBenchmarkConfigTest {

    @Test
    void smokeModeUsesQuickSingleConcurrencyDefaults() {
        DeploymentBenchmarkConfig config = DeploymentBenchmarkConfig.forMode("smoke", Path.of("results"));

        assertThat(config.mode()).isEqualTo("smoke");
        assertThat(config.remoteConcurrencyLevels()).containsExactly(1);
        assertThat(config.redisConcurrencyLevels()).containsExactly(1);
        assertThat(config.warmupAttemptsPerThread()).isPositive();
        assertThat(config.measuredAttemptsPerThread()).isPositive();
    }

    @Test
    void fullModeUsesExpectedConcurrencyLevels() {
        DeploymentBenchmarkConfig config = DeploymentBenchmarkConfig.forMode("full", Path.of("results"));

        assertThat(config.remoteConcurrencyLevels()).containsExactly(1, 4, 16);
        assertThat(config.redisConcurrencyLevels()).containsExactly(1, 4, 16);
        assertThat(config.runRemoteNormal()).isTrue();
        assertThat(config.runRedisNormal()).isTrue();
        assertThat(config.runDegradation()).isTrue();
    }

    @Test
    void invalidModeIsRejected() {
        assertThatThrownBy(() -> DeploymentBenchmarkConfig.forMode("nope", Path.of("results")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported mode");
    }
}
