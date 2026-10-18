package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.IsolationForestModel;
import dev.aisentinel.core.scoring.IsolationForestTrainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Offline-only Isolation Forest {@link AnomalyScorer} for Level-3 same-framework comparison.
 * <p>
 * Consumes the six-dimensional statistical feature projection via
 * {@link RequestFeatures#toStatisticalArray()} — not the production five-feature
 * {@link RequestFeatures#toIsolationForestArray()} path.
 * <p>
 * Training uses only the first {@code minTrainingSamples} detector-visible {@link #update}
 * observations (state-building population). Ground-truth labels are never consulted.
 * After the first train completes, the model is frozen (no retraining, no post-result fitting).
 * <p>
 * Synchronous, deterministic, no background threads, no registry, no metrics, no sampling.
 * Distinct from production {@code IsolationForestScorer}.
 */
final class ReferenceIsolationForestScorer implements AnomalyScorer {

    private final ReferenceIsolationForestConfig config;
    private final IsolationForestTrainer trainer;
    private final Object lock = new Object();
    private final List<double[]> trainingBuffer = new ArrayList<>();
    private IsolationForestModel model;
    private boolean trainingClosed;

    ReferenceIsolationForestScorer(ReferenceIsolationForestConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.trainer = new IsolationForestTrainer(
            config.numTrees(),
            config.maxDepth(),
            config.randomSeed()
        );
    }

    ReferenceIsolationForestConfig config() {
        return config;
    }

    boolean modelTrained() {
        synchronized (lock) {
            return model != null;
        }
    }

    int trainingSampleCountAccepted() {
        synchronized (lock) {
            return trainingClosed ? config.minTrainingSamples() : trainingBuffer.size();
        }
    }

    @Override
    public double score(RequestFeatures features) {
        Objects.requireNonNull(features, "features");
        IsolationForestModel local;
        synchronized (lock) {
            local = model;
        }
        if (local == null) {
            return config.fallbackScore();
        }
        double[] x = features.toStatisticalArray();
        FeatureSchema.requireStatisticalDimension(x);
        double raw = local.score(x);
        if (!Double.isFinite(raw) || raw < 0.0) {
            return config.fallbackScore();
        }
        return Math.min(1.0, raw);
    }

    @Override
    public void update(RequestFeatures features) {
        Objects.requireNonNull(features, "features");
        synchronized (lock) {
            if (trainingClosed) {
                return;
            }
            double[] x = features.toStatisticalArray();
            FeatureSchema.requireStatisticalDimension(x);
            trainingBuffer.add(x.clone());
            if (trainingBuffer.size() >= config.minTrainingSamples()) {
                IsolationForestModel trained = trainer.train(List.copyOf(trainingBuffer));
                if (trained == null) {
                    throw new IllegalStateException(
                        "Reference Isolation Forest training produced no model from "
                            + trainingBuffer.size() + " samples");
                }
                if (trained.featureDimension() != FeatureSchema.STATISTICAL_DIMENSION) {
                    throw new IllegalStateException(
                        "Reference Isolation Forest model dimension mismatch: expected "
                            + FeatureSchema.STATISTICAL_DIMENSION
                            + ", actual " + trained.featureDimension());
                }
                model = trained;
                trainingClosed = true;
                trainingBuffer.clear();
            }
        }
    }
}
