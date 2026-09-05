package dev.aisentinel.benchmark.fixture;

import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.store.BaselineStore;

import java.time.Duration;

/**
 * Pipeline assembly for in-process benchmarks.
 */
public final class BenchmarkPipelineFactory {

    private BenchmarkPipelineFactory() {
    }

    public static AssembledPipeline statisticalPipeline() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 100_000);
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(store, 100_000, 300_000L);
        StatisticalScorer scorer = BenchmarkComponentFactory.newStatisticalScorer();
        SentinelPipeline pipeline = new SentinelPipeline(
            extractor,
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            PassThroughEnforcementHandler.INSTANCE,
            event -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP
        );
        return new AssembledPipeline(pipeline, extractor, scorer, store);
    }

    public record AssembledPipeline(
        SentinelPipeline pipeline,
        DefaultFeatureExtractor extractor,
        AnomalyScorer scorer,
        BaselineStore baselineStore
    ) {
    }
}
