package dev.aisentinel.core.scoring;

import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Weighted combination of anomaly scorers.
 * When IsolationForest is disabled, only StatisticalScorer is used.
 * <p>
 * After each {@link #score(RequestFeatures)} call, exposes a snapshot of component scores for
 * operations and A/B-style comparison (statistical vs Isolation Forest vs blended composite).
 * {@link #getLastCompositeScoreSnapshot()} is <strong>diagnostic only</strong> (last invocation
 * globally) — request-owned explanation uses {@link #scoreWithExplanation(RequestFeatures)}.
 * <p>
 * Isolation Forest contributes to the weighted blend <strong>only</strong> when its score mode
 * for <em>this</em> invocation is {@link IsolationForestScorer.LastScoreMode#MODEL}. Configured
 * fallback scores remain visible on the snapshot but do not dilute the composite.
 * <p>
 * Child scorers are held in a {@link CopyOnWriteArrayList} so {@link #score}/{@link #update}
 * always iterate a stable snapshot even if {@link #addScorer} runs concurrently.
 */
public final class CompositeScorer implements AnomalyScorer {

    private final List<WeightedScorer> scorers = new CopyOnWriteArrayList<>();
    private final SentinelMetrics metrics;

    /** Most recent per-scorer values from the last score invocation (volatile publish; diagnostic). */
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
     * Registered child scorers (evaluation-status collection). Order matches blend registration.
     * Not a public SPI extension point — package consumers only.
     */
    public java.util.List<AnomalyScorer> scorersView() {
        java.util.List<AnomalyScorer> view = new java.util.ArrayList<>(scorers.size());
        for (WeightedScorer ws : scorers) {
            view.add(ws.scorer);
        }
        return List.copyOf(view);
    }

    /**
     * Snapshot from the last score invocation on this instance, or {@code null} if never scored.
     * <p>
     * <strong>Diagnostic only:</strong> last scorer invocation globally — not request-scoped.
     */
    public CompositeScoreSnapshot getLastCompositeScoreSnapshot() {
        return lastSnapshot;
    }

    /**
     * Scores and returns immutable component explanations for <em>this</em> invocation (request-safe).
     * Also publishes {@link #getLastCompositeScoreSnapshot()} for diagnostics.
     */
    public CompositeScoreOutcome scoreWithExplanation(RequestFeatures features) {
        if (scorers.isEmpty()) {
            metrics.recordCompositeScore(0.0);
            lastSnapshot = null;
            return new CompositeScoreOutcome(0.0, null, null);
        }
        double sum = 0;
        double totalWeight = 0;
        double statistical = Double.NaN;
        Double isolationForest = null;
        boolean isolationForestIncludedInBlend = false;
        String isolationForestScoreMode = null;
        StatisticalScoreSnapshot statisticalSnapshot = null;
        Double invalidScore = null;
        for (WeightedScorer ws : scorers) {
            double s;
            boolean includeInBlend = true;
            if (ws.scorer instanceof StatisticalScorer statisticalScorer) {
                StatisticalScorer.StatisticalScoreOutcome st = statisticalScorer.scoreWithExplanation(features);
                s = st.score();
                statistical = s;
                statisticalSnapshot = st.snapshot();
            } else if (ws.scorer instanceof IsolationForestScorer ifScorer) {
                IsolationForestScorer.IsolationForestScoreOutcome ifOut = ifScorer.scoreWithMode(features);
                s = ifOut.score();
                isolationForest = s;
                IsolationForestScorer.LastScoreMode mode = ifOut.mode();
                isolationForestScoreMode = mode.name();
                includeInBlend = mode == IsolationForestScorer.LastScoreMode.MODEL;
                isolationForestIncludedInBlend = includeInBlend;
            } else {
                s = ws.scorer.score(features);
            }
            if (isInvalidScore(s)) {
                if (invalidScore == null) {
                    invalidScore = s;
                }
                includeInBlend = false;
            }
            if (includeInBlend) {
                sum += s * ws.weight;
                totalWeight += ws.weight;
            }
        }
        if (totalWeight <= 0) {
            if (invalidScore != null) {
                long now = System.currentTimeMillis();
                CompositeScoreSnapshot snap = new CompositeScoreSnapshot(
                    statistical, isolationForest, invalidScore, now,
                    isolationForestIncludedInBlend, isolationForestScoreMode);
                lastSnapshot = snap;
                return new CompositeScoreOutcome(invalidScore, snap, statisticalSnapshot);
            }
            metrics.recordCompositeScore(0.0);
            lastSnapshot = null;
            return new CompositeScoreOutcome(0.0, null, statisticalSnapshot);
        }
        double raw = sum / totalWeight;
        long now = System.currentTimeMillis();
        // Pass NaN / ±Infinity / negative through to the decision engine (INVALID_SCORE).
        // Do not convert them to 1.0 — that conflated evaluation failure with maximum risk.
        // Finite values > 1 are range-clamped below (not INVALID_SCORE).
        if (Double.isNaN(raw) || Double.isInfinite(raw) || raw < 0) {
            CompositeScoreSnapshot snap = new CompositeScoreSnapshot(
                statistical, isolationForest, raw, now,
                isolationForestIncludedInBlend, isolationForestScoreMode);
            lastSnapshot = snap;
            return new CompositeScoreOutcome(raw, snap, statisticalSnapshot);
        }
        double out = Math.min(1.0, raw);
        metrics.recordCompositeScore(out);
        CompositeScoreSnapshot snap = new CompositeScoreSnapshot(
            statistical, isolationForest, out, now,
            isolationForestIncludedInBlend, isolationForestScoreMode);
        lastSnapshot = snap;
        return new CompositeScoreOutcome(out, snap, statisticalSnapshot);
    }

    @Override
    public double score(RequestFeatures features) {
        return scoreWithExplanation(features).score();
    }

    @Override
    public void update(RequestFeatures features) {
        for (WeightedScorer ws : scorers) {
            ws.scorer.update(features);
        }
    }

    private static boolean isInvalidScore(double score) {
        return Double.isNaN(score) || Double.isInfinite(score) || score < 0;
    }

    private record WeightedScorer(AnomalyScorer scorer, double weight) {}

    /**
     * Request-owned result of one {@link #scoreWithExplanation} call.
     */
    public record CompositeScoreOutcome(
        double score,
        CompositeScoreSnapshot compositeSnapshot,
        StatisticalScoreSnapshot statisticalSnapshot
    ) {
    }

    /**
     * Point-in-time component scores after a composite evaluation (for actuator / validation).
     * {@code statistical} may be NaN if no {@link StatisticalScorer} is registered;
     * {@code isolationForest} is null when no {@link IsolationForestScorer} is registered.
     * {@code isolationForestIncludedInBlend} is true only when IF mode was {@code MODEL}.
     */
    public record CompositeScoreSnapshot(
        double statistical,
        Double isolationForest,
        double composite,
        long evaluatedAtEpochMillis,
        boolean isolationForestIncludedInBlend,
        String isolationForestScoreMode
    ) {
        /** Compatibility constructor when IF mode fields are unknown. */
        public CompositeScoreSnapshot(double statistical, Double isolationForest, double composite,
                                      long evaluatedAtEpochMillis) {
            this(statistical, isolationForest, composite, evaluatedAtEpochMillis, false, null);
        }
    }
}
