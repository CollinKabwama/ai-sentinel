package dev.aisentinel.benchmark.deployment;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceBenchmarkConfigTest {

    @Test
    void smokeModeUsesShortSingleConcurrencyDefaults() {
        ResourceBenchmarkConfig config = ResourceBenchmarkConfig.forMode("smoke", Path.of("results"));

        assertThat(config.mode()).isEqualTo("smoke");
        assertThat(config.inProcessConcurrency()).containsExactly(1);
        assertThat(config.remoteConcurrency()).containsExactly(1);
        assertThat(config.redisConcurrency()).containsExactly(1);
        assertThat(config.measurementDuration()).isPositive();
    }

    @Test
    void fullModeUsesExpectedConcurrencyLevels() {
        ResourceBenchmarkConfig config = ResourceBenchmarkConfig.forMode("full", Path.of("results"));

        assertThat(config.inProcessConcurrency()).containsExactly(1, 4, 16);
        assertThat(config.remoteConcurrency()).containsExactly(1, 4, 16);
        assertThat(config.redisConcurrency()).containsExactly(1, 4, 16);
    }

    @Test
    void invalidModeIsRejected() {
        assertThatThrownBy(() -> ResourceBenchmarkConfig.forMode("nope", Path.of("results")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported resource mode");
    }
}
