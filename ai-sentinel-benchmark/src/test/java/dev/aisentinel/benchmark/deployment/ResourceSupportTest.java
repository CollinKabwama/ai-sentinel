package dev.aisentinel.benchmark.deployment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceSupportTest {

    @Test
    void cpuCoresEquivalentUsesCpuTimeDividedByWallTime() {
        assertThat(ResourceSupport.processCpuCoresEquivalent(2_000_000_000L, 1_000_000_000L))
            .isEqualTo(2.0);
    }

    @Test
    void cpuPercentOfMachineNormalizesByLogicalProcessors() {
        assertThat(ResourceSupport.processCpuPercentOfMachine(2_000_000_000L, 1_000_000_000L, 4))
            .isEqualTo(50.0);
    }

    @Test
    void nullCpuInputYieldsUnavailableMetrics() {
        assertThat(ResourceSupport.processCpuCoresEquivalent(null, 1_000_000_000L)).isNull();
        assertThat(ResourceSupport.processCpuPercentOfMachine(null, 1_000_000_000L, 4)).isNull();
    }
}
