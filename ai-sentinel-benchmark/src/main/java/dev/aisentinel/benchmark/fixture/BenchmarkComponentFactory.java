package dev.aisentinel.benchmark.fixture;

import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.BoundedTrainingBuffer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestConfig;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;

/**
 * Factory helpers for reproducible in-process scorers and decision engines.
 */
public final class BenchmarkComponentFactory {

    private BenchmarkComponentFactory() {
    }

    public static StatisticalScorer newStatisticalScorer() {
        return new StatisticalScorer(100_000, 300_000L, 2, 0.4);
    }

    /**
     * Isolation Forest scorer with a loaded model from synthetic training samples.
     */
    public static IsolationForestScorer newIsolationForestWithModel() {
        BoundedTrainingBuffer buffer = new BoundedTrainingBuffer(500);
        IsolationForestConfig config = new IsolationForestConfig(0.5, 50, 20, 8, 42L, 1.0);
        IsolationForestScorer scorer = new IsolationForestScorer(buffer, config);
        for (int i = 0; i < 100; i++) {
            buffer.add(new double[]{i % 10, 0.5, 60, 2, 100 + i});
        }
        scorer.retrain();
        if (!scorer.isModelLoaded()) {
            throw new IllegalStateException("Isolation Forest model failed to load for benchmarks");
        }
        return scorer;
    }

    /** Isolation Forest without a model (fallback-score path). */
    public static IsolationForestScorer newIsolationForestWithoutModel() {
        BoundedTrainingBuffer buffer = new BoundedTrainingBuffer(100);
        IsolationForestConfig config = new IsolationForestConfig(0.5, 50, 10, 5, 42L, 1.0);
        return new IsolationForestScorer(buffer, config);
    }

    /** Statistical + Isolation Forest composite with IF weight only when model is present. */
    public static CompositeScorer newCompositeWithIsolationForestModel() {
        CompositeScorer composite = new CompositeScorer();
        composite.addScorer(newStatisticalScorer(), 1.0);
        composite.addScorer(newIsolationForestWithModel(), 0.5);
        return composite;
    }

    /** Statistical-only composite (matches default product posture with IF disabled). */
    public static CompositeScorer newStatisticalOnlyComposite() {
        CompositeScorer composite = new CompositeScorer();
        composite.addScorer(newStatisticalScorer(), 1.0);
        return composite;
    }

    public static SentinelDecisionEngine newDecisionEngine(AnomalyScorer scorer) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            PassThroughEnforcementHandler.INSTANCE,
            event -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
    }

    /** Seed statistical state so timed iterations are past warmup. */
    public static void seedEstablishedBaseline(StatisticalScorer scorer, String identity, String endpoint, int updates) {
        RequestFeatures baseline = BenchmarkFeatureFactory.establishedBaseline(identity, endpoint);
        for (int i = 0; i < updates; i++) {
            scorer.update(baseline);
        }
        // Consume one score so callers can assert finite output before timing.
        double score = scorer.score(baseline);
        if (Double.isNaN(score) || Double.isInfinite(score) || score < 0.0) {
            throw new IllegalStateException("Unexpected statistical score after baseline seeding: " + score);
        }
    }

    public static AnomalyScorer invalidScoreScorer() {
        return new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return Double.NaN;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
    }
}
