package io.aisentinel.core.scoring;

import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.model.RequestFeatures;

import java.util.ArrayList;
import java.util.List;

/**
 * Weighted combination of anomaly scorers.
 * When IsolationForest is disabled, only StatisticalScorer is used.
 * <p>
 * After each {@link #score(RequestFeatures)} call, exposes a snapshot of component scores for
 * operations and A/B-style comparison (statistical vs Isolation Forest vs blended composite).
 */
public final class CompositeScorer implements AnomalyScorer {

    private final List<WeightedScorer> scorers = new ArrayList<>();
    private final SentinelMetrics metrics;

    /** Most recent per-scorer values from the last {@link #score} invocation (volatile publish). */
    private volatile CompositeScoreSnapshot lastSnapshot;

    public CompositeScorer() {
        this(SentinelMetrics.NOOP);
    }

    public CompositeScorer(SentinelMetrics metrics) {
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
    }

    public void addScorer(AnomalyScorer scorer, double weight) {
        if (weight > 0) {
            scorers.add(new WeightedScorer(scorer, weight));
        }
    }

    /**
     * Snapshot from the last {@link #score(RequestFeatures)} call, or {@code null} if {@link #score} has never run.
     */
    public CompositeScoreSnapshot getLastCompositeScoreSnapshot() {
        return lastSnapshot;
    }

    @Override
    public double score(RequestFeatures features) {
        if (scorers.isEmpty()) {
            metrics.recordCompositeScore(0.0);
            lastSnapshot = null;
            return 0.0;
        }
        double sum = 0;
        double totalWeight = 0;
        double statistical = Double.NaN;
        Double isolationForest = null;
        for (WeightedScorer ws : scorers) {
            double s = ws.scorer.score(features);
            if (ws.scorer instanceof StatisticalScorer) {
                statistical = s;
            } else if (ws.scorer instanceof IsolationForestScorer) {
                isolationForest = s;
            }
            sum += s * ws.weight;
            totalWeight += ws.weight;
        }
        if (totalWeight <= 0) {
            metrics.recordCompositeScore(0.0);
            lastSnapshot = null;
            return 0.0;
        }
        double raw = sum / totalWeight;
        if (Double.isNaN(raw) || raw < 0) {
            metrics.recordCompositeScore(1.0);
            lastSnapshot = new CompositeScoreSnapshot(statistical, isolationForest, 1.0, System.currentTimeMillis());
            return 1.0;
        }
        double out = Math.min(1.0, raw);
        metrics.recordCompositeScore(out);
        lastSnapshot = new CompositeScoreSnapshot(statistical, isolationForest, out, System.currentTimeMillis());
        return out;
    }

    @Override
    public void update(RequestFeatures features) {
        for (WeightedScorer ws : scorers) {
            ws.scorer.update(features);
        }
    }

    private record WeightedScorer(AnomalyScorer scorer, double weight) {}

    /**
     * Point-in-time component scores after the latest composite evaluation (for actuator / validation).
     * {@code statistical} may be NaN if no {@link StatisticalScorer} is registered;
     * {@code isolationForest} is null when no {@link IsolationForestScorer} is registered.
     */
    public record CompositeScoreSnapshot(
        double statistical,
        Double isolationForest,
        double composite,
        long evaluatedAtEpochMillis
    ) {}
}
