package dev.aisentinel.benchmark.deployment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Successful and failed operation latencies are summarized independently.
 *
 * <p>Percentiles use the nearest-rank method on sorted per-operation latency samples collected after warmup.
 * Values are reported in milliseconds. Failed operations are never folded into successful latency percentiles.
 */
record LatencyStats(
    String unit,
    String percentileAlgorithm,
    int sampleCount,
    Double p50,
    Double p95,
    Double p99
) {

    static final String UNIT_MILLISECONDS = "milliseconds";
    static final String NEAREST_RANK = "nearest-rank";

    static LatencyStats fromNanos(List<Long> samples) {
        if (samples == null || samples.isEmpty()) {
            return new LatencyStats(UNIT_MILLISECONDS, NEAREST_RANK, 0, null, null, null);
        }
        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        return new LatencyStats(
            UNIT_MILLISECONDS,
            NEAREST_RANK,
            sorted.size(),
            toMillis(rank(sorted, 0.50)),
            toMillis(rank(sorted, 0.95)),
            toMillis(rank(sorted, 0.99)));
    }

    private static long rank(List<Long> sorted, double percentile) {
        int n = sorted.size();
        int index = (int) Math.ceil(percentile * n) - 1;
        index = Math.max(0, Math.min(index, n - 1));
        return sorted.get(index);
    }

    private static double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
