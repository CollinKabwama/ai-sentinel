package io.aisentinel.core.scoring;

import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.model.RequestFeatures;

import java.util.ArrayList;
import java.util.List;

/**
 * Weighted combination of anomaly scorers.
 * When IsolationForest is disabled, only StatisticalScorer is used.
 */
public final class CompositeScorer implements AnomalyScorer {

    private final List<WeightedScorer> scorers = new ArrayList<>();
    private final SentinelMetrics metrics;

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

    @Override
    public double score(RequestFeatures features) {
        if (scorers.isEmpty()) {
            metrics.recordCompositeScore(0.0);
            return 0.0;
        }
        double sum = 0;
        double totalWeight = 0;
        for (WeightedScorer ws : scorers) {
            double s = ws.scorer.score(features);
            sum += s * ws.weight;
            totalWeight += ws.weight;
        }
        if (totalWeight <= 0) {
            metrics.recordCompositeScore(0.0);
            return 0.0;
        }
        double raw = sum / totalWeight;
        if (Double.isNaN(raw) || raw < 0) {
            metrics.recordCompositeScore(1.0);
            return 1.0;
        }
        double out = Math.min(1.0, raw);
        metrics.recordCompositeScore(out);
        return out;
    }

    @Override
    public void update(RequestFeatures features) {
        for (WeightedScorer ws : scorers) {
            ws.scorer.update(features);
        }
    }

    private record WeightedScorer(AnomalyScorer scorer, double weight) {}
}
