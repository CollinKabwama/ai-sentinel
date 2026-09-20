package dev.aisentinel.benchmark.deployment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyStatsTest {

    @Test
    void emptySamplesProduceNullPercentiles() {
        LatencyStats stats = LatencyStats.fromNanos(List.of());

        assertThat(stats.sampleCount()).isZero();
        assertThat(stats.p50()).isNull();
        assertThat(stats.p95()).isNull();
        assertThat(stats.p99()).isNull();
    }

    @Test
    void singleSampleUsesThatSampleForAllPercentiles() {
        LatencyStats stats = LatencyStats.fromNanos(List.of(7_000_000L));

        assertThat(stats.sampleCount()).isEqualTo(1);
        assertThat(stats.p50()).isEqualTo(7.0);
        assertThat(stats.p95()).isEqualTo(7.0);
        assertThat(stats.p99()).isEqualTo(7.0);
    }

    @Test
    void nearestRankPercentilesUseSortedSuccessfulSamples() {
        LatencyStats stats = LatencyStats.fromNanos(List.of(
            5_000_000L,
            1_000_000L,
            4_000_000L,
            2_000_000L,
            3_000_000L));

        assertThat(stats.sampleCount()).isEqualTo(5);
        assertThat(stats.p50()).isEqualTo(3.0);
        assertThat(stats.p95()).isEqualTo(5.0);
        assertThat(stats.p99()).isEqualTo(5.0);
        assertThat(stats.unit()).isEqualTo("milliseconds");
        assertThat(stats.percentileAlgorithm()).isEqualTo("nearest-rank");
    }

    @Test
    void evenSampleNearestRankUsesCeilingRank() {
        LatencyStats stats = LatencyStats.fromNanos(List.of(4_000_000L, 1_000_000L, 2_000_000L, 3_000_000L));

        assertThat(stats.p50()).isEqualTo(2.0);
        assertThat(stats.p95()).isEqualTo(4.0);
        assertThat(stats.p99()).isEqualTo(4.0);
    }
}
