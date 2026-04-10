package io.aisentinel.core.scoring;

import io.aisentinel.core.model.RequestFeatures;

/**
 * Computes a per-request anomaly sub-score from {@link RequestFeatures}.
 * <p>
 * Used on the <strong>request path</strong> inside {@link io.aisentinel.core.SentinelPipeline}. Implementations must be
 * thread-safe for concurrent {@link #score} / {@link #update} calls and should avoid blocking I/O. Scores returned from
 * {@link #score} are expected in {@code [0.0, 1.0]} (higher = more anomalous); NaN or negative values may be clamped
 * downstream by the composite scorer.
 */
public interface AnomalyScorer {

    /**
     * Produce a blended or per-model score for policy evaluation.
     *
     * @param features features for the current request (must not be mutated)
     * @return anomaly score in {@code [0.0, 1.0]} under normal conditions
     */
    double score(RequestFeatures features);

    /**
     * Optional training / online update: sample a subset of traffic for bounded buffers (e.g. Isolation Forest).
     * Must be cheap; must not block the request thread on network I/O.
     *
     * @param features same request as {@link #score}
     */
    void update(RequestFeatures features);
}
