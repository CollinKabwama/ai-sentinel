package dev.aisentinel.core.scoring.artifact;

import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.IsolationForestModel;

import java.util.Objects;

/**
 * Candidate-only Isolation Forest scorer bound to a verified, decoded model.
 * <p>
 * Does not train, does not mutate behavioral baselines, and is not wired into
 * production decision authority by this type alone. Scoring semantics follow the
 * loaded-model path of {@link dev.aisentinel.core.scoring.IsolationForestScorer}:
 * invalid numeric outputs ({@code NaN}, {@code ±Infinity}, negative) are
 * propagated so callers may classify {@code INVALID_SCORE}
 * ({@code INVALID SCORE != MAXIMUM RISK}).
 * <p>
 * The underlying {@link IsolationForestModel} is immutable; concurrent
 * {@link #score(RequestFeatures)} calls are safe. {@link #update(RequestFeatures)}
 * is intentionally a no-op.
 */
final class CandidateIsolationForestScorer implements AnomalyScorer {

    static final String RUNTIME_IMPLEMENTATION_ID = "isolation_forest_model_codec_v1";

    private final IsolationForestModel model;

    CandidateIsolationForestScorer(IsolationForestModel model) {
        this.model = Objects.requireNonNull(model, "model");
    }

    IsolationForestModel model() {
        return model;
    }

    @Override
    public double score(RequestFeatures features) {
        Objects.requireNonNull(features, "features");
        double[] x = features.toIsolationForestArray();
        double s = model.score(x);
        if (Double.isNaN(s) || Double.isInfinite(s) || s < 0) {
            return s;
        }
        return Math.min(1.0, Math.max(0.0, s));
    }

    @Override
    public void update(RequestFeatures features) {
        // Candidate loading/health must not mutate behavioral learning state.
    }
}
