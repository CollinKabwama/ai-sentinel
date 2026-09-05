package dev.aisentinel.benchmark.fixture;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.store.BaselineStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkFixtureCorrectnessTest {

    @Test
    void featureFactoryBuildsDistinctWorkloads() {
        RequestFeatures baseline = BenchmarkFeatureFactory.establishedBaseline("a", "/x");
        RequestFeatures abrupt = BenchmarkFeatureFactory.abruptVolumeDeviation("a", "/x");
        assertThat(abrupt.requestsPerWindow()).isGreaterThan(baseline.requestsPerWindow());
        assertThat(baseline.identityHash()).isEqualTo("a");
    }

    @Test
    void statisticalSeedingProducesFiniteScore() {
        StatisticalScorer scorer = BenchmarkComponentFactory.newStatisticalScorer();
        BenchmarkComponentFactory.seedEstablishedBaseline(scorer, "id", "/api", 32);
        double score = scorer.score(BenchmarkFeatureFactory.establishedBaseline("id", "/api"));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void isolationForestModelLoadsForBenchmarks() {
        IsolationForestScorer scorer = BenchmarkComponentFactory.newIsolationForestWithModel();
        assertThat(scorer.isModelLoaded()).isTrue();
        double score = scorer.score(BenchmarkFeatureFactory.establishedBaseline("id", "/api"));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void compositeIncludesIsolationForestChild() {
        CompositeScorer composite = BenchmarkComponentFactory.newCompositeWithIsolationForestModel();
        assertThat(composite.scorersView()).hasSize(2);
        double score = composite.score(BenchmarkFeatureFactory.establishedBaseline("id", "/api"));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void invalidScorePathSurfacesInvalidStatus() {
        SentinelDecisionEngine engine =
            BenchmarkComponentFactory.newDecisionEngine(BenchmarkComponentFactory.invalidScoreScorer());
        RiskDecision decision = engine.evaluate(
            BenchmarkHttpRequestView.typical(),
            "id",
            BenchmarkFeatureFactory.establishedBaseline("id", "/api/benchmark"),
            new RequestContext()
        );
        assertThat(decision.hasStatus(EvaluationStatus.INVALID_SCORE)).isTrue();
    }

    @Test
    void pipelineProcessesTypicalRequest() {
        var assembled = BenchmarkPipelineFactory.statisticalPipeline();
        boolean proceed = assembled.pipeline().process(
            BenchmarkHttpRequestView.typical(),
            NoopEnforcementResponse.INSTANCE,
            "pipeline-id"
        );
        assertThat(proceed).isTrue();
    }

    @Test
    void largerValidRequestExercisesPayloadAndParameterExtraction() {
        var extractor = new dev.aisentinel.core.feature.DefaultFeatureExtractor(
            new BaselineStore(Duration.ofMinutes(5), 100_000));
        RequestFeatures features = extractor.extract(
            BenchmarkHttpRequestView.largerValid(),
            "feature-id",
            new RequestContext());
        assertThat(features.parameterCount()).isEqualTo(16);
        assertThat(features.payloadSizeBytes()).isEqualTo(32_768);
        assertThat(features.tokenAgeSeconds()).isBetween(0.0, 300.0);
    }
}
