package dev.aisentinel.core.pilot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accumulates operational summary statistics from pilot observations.
 */
public final class PilotSummaryAccumulator {

    private final String pilotSessionId;
    private final Set<String> identities = new HashSet<>();
    private final Map<String, Long> evaluationStatuses = new HashMap<>();
    private final Map<String, Long> riskDerivedActions = new HashMap<>();
    private long observations;
    private long baselineAccepted;
    private long baselineSkipped;
    private long baselineUnavailable;
    private long invalidScoreCount;
    private long degradedCount;
    private long failOpenCount;
    private long scoreCount;
    private double scoreSum;
    private double scoreMin = Double.POSITIVE_INFINITY;
    private double scoreMax = Double.NEGATIVE_INFINITY;
    private final List<Long> latencies = new ArrayList<>();

    public PilotSummaryAccumulator(String pilotSessionId) {
        this.pilotSessionId = pilotSessionId;
    }

    public synchronized void accept(PilotObservation observation) {
        observations++;
        identities.add(observation.pseudonymousIdentity());
        for (String status : observation.evaluationStatuses()) {
            evaluationStatuses.merge(status, 1L, Long::sum);
            if ("INVALID_SCORE".equals(status)) {
                invalidScoreCount++;
            }
            if ("DEGRADED".equals(status)) {
                degradedCount++;
            }
        }
        riskDerivedActions.merge(observation.riskDerivedAction(), 1L, Long::sum);
        switch (observation.baselineUpdateStatus()) {
            case "ACCEPTED" -> baselineAccepted++;
            case "SKIPPED" -> baselineSkipped++;
            default -> baselineUnavailable++;
        }
        if (observation.failOpenReason() != null && !observation.failOpenReason().isBlank()) {
            failOpenCount++;
        }
        if (Double.isFinite(observation.anomalyScore())) {
            scoreCount++;
            scoreSum += observation.anomalyScore();
            scoreMin = Math.min(scoreMin, observation.anomalyScore());
            scoreMax = Math.max(scoreMax, observation.anomalyScore());
        }
        if (observation.pipelineLatencyNanos() != null && observation.pipelineLatencyNanos() >= 0) {
            latencies.add(observation.pipelineLatencyNanos());
        }
    }

    public synchronized PilotSummary build() {
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        long p50 = percentile(sorted, 0.50);
        long p95 = percentile(sorted, 0.95);
        double mean = scoreCount == 0 ? 0.0 : scoreSum / scoreCount;
        double min = scoreCount == 0 ? 0.0 : scoreMin;
        double max = scoreCount == 0 ? 0.0 : scoreMax;
        return new PilotSummary(
            "1",
            pilotSessionId,
            PilotObservation.EVIDENCE_CLASS,
            observations,
            identities.size(),
            PilotSummary.sortedCounts(evaluationStatuses),
            PilotSummary.sortedCounts(riskDerivedActions),
            baselineAccepted,
            baselineSkipped,
            baselineUnavailable,
            invalidScoreCount,
            degradedCount,
            failOpenCount,
            scoreCount,
            min,
            max,
            mean,
            sorted.size(),
            p50,
            p95
        );
    }

    private static long percentile(List<Long> sortedAscending, double p) {
        if (sortedAscending.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(p * sortedAscending.size()) - 1;
        if (index < 0) {
            index = 0;
        }
        if (index >= sortedAscending.size()) {
            index = sortedAscending.size() - 1;
        }
        return sortedAscending.get(index);
    }
}
