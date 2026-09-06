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

    @Test
    void negativeCpuInputYieldsUnavailableMetrics() {
        assertThat(ResourceSupport.processCpuCoresEquivalent(-1L, 1_000_000_000L)).isNull();
        assertThat(ResourceSupport.processCpuPercentOfMachine(-1L, 1_000_000_000L, 4)).isNull();
    }

    @Test
    void parsesDockerMemoryUnitsAsBytes() {
        assertThat(ResourceSupport.parseMemoryUsage("512B / 1GiB")).isEqualTo(512L);
        assertThat(ResourceSupport.parseMemoryUsage("1.5kB / 1GB")).isEqualTo(1_500L);
        assertThat(ResourceSupport.parseMemoryUsage("1.5KiB / 1GiB")).isEqualTo(1536L);
        assertThat(ResourceSupport.parseMemoryUsage("10.25MB / 1GB")).isEqualTo(10_250_000L);
        assertThat(ResourceSupport.parseMemoryUsage("10.25MiB / 1GiB")).isEqualTo(10_747_904L);
        assertThat(ResourceSupport.parseMemoryUsage("2GB / 4GB")).isEqualTo(2_000_000_000L);
        assertThat(ResourceSupport.parseMemoryUsage("2GiB / 4GiB")).isEqualTo(2_147_483_648L);
    }

    @Test
    void invalidDockerMemoryUnitYieldsUnavailableMetric() {
        assertThat(ResourceSupport.parseMemoryUsage("10XB / 1GiB")).isNull();
        assertThat(ResourceSupport.parseMemoryUsage("not-memory")).isNull();
        assertThat(ResourceSupport.parseMemoryUsage(null)).isNull();
    }

    @Test
    void parsesDockerCpuPercent() {
        assertThat(ResourceSupport.parsePercent("12.34%")).isEqualTo(12.34);
        assertThat(ResourceSupport.parsePercent("")).isNull();
        assertThat(ResourceSupport.parsePercent("not-percent")).isNull();
    }
}
