package dev.aisentinel.benchmark.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComparisonPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsDefaultPolicy() throws Exception {
        ComparisonPolicy policy = ComparisonPolicy.loadDefault();

        assertThat(policy.schemaVersion()).isEqualTo("1");
        assertThat(policy.sha256()).hasSize(64);
    }

    @Test
    void rejectsInvalidThresholdOrdering() throws Exception {
        Path invalid = tempDir.resolve("invalid-policy.json");
        Files.writeString(invalid, """
            {
              "policySchemaVersion": "1",
              "policies": [
                {
                  "family": "JMH",
                  "benchmarkId": "b",
                  "params": {},
                  "metric": "p50",
                  "direction": "LOWER_IS_BETTER",
                  "warnThresholdPercent": 30.0,
                  "regressionThresholdPercent": 20.0,
                  "gateEligible": true
                }
              ]
            }
            """);

        assertThatThrownBy(() -> ComparisonPolicy.load(invalid))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("warnThresholdPercent must be <= regressionThresholdPercent");
    }
}
