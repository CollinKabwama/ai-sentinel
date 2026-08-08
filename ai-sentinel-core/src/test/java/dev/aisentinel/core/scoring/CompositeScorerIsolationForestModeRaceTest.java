package dev.aisentinel.core.scoring;

import dev.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent MODEL vs FALLBACK IF modes must not cross-contaminate composite explanation.
 * Fidelity: scorer-only.
 */
class CompositeScorerIsolationForestModeRaceTest {

    private static final RequestFeatures FEATURES = RequestFeatures.builder()
        .identityHash("id")
        .endpoint("/api")
        .timestampMillis(0)
        .requestsPerWindow(1)
        .endpointEntropy(0)
        .tokenAgeSeconds(60)
        .parameterCount(0)
        .payloadSizeBytes(0)
        .headerFingerprintHash(0)
        .ipBucket(0)
        .build();

    @Test
    void concurrentModelAndFallback_outcomesKeepOwnModes() throws Exception {
        var buffer = new BoundedTrainingBuffer(500);
        for (int i = 0; i < 100; i++) {
            buffer.add(new double[] {i % 10, 0.5, 60, 2, 100 + i});
        }
        IsolationForestScorer withModel = new IsolationForestScorer(
            buffer, new IsolationForestConfig(0.5, 50, 20, 8, 42L, 1.0));
        withModel.retrain();
        assertThat(withModel.isModelLoaded()).isTrue();

        IsolationForestScorer noModel = new IsolationForestScorer(
            new BoundedTrainingBuffer(10),
            new IsolationForestConfig(0.5, 50, 20, 8, 42L, 1.0));

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<CompositeScorer.CompositeScoreOutcome> modelOut = new AtomicReference<>();
        AtomicReference<CompositeScorer.CompositeScoreOutcome> fallbackOut = new AtomicReference<>();

        CompositeScorer modelComposite = new CompositeScorer();
        modelComposite.addScorer(new StatisticalScorer(100, 60_000L, 999, 0.1), 1.0);
        modelComposite.addScorer(withModel, 0.5);

        CompositeScorer fallbackComposite = new CompositeScorer();
        fallbackComposite.addScorer(new StatisticalScorer(100, 60_000L, 999, 0.1), 1.0);
        fallbackComposite.addScorer(noModel, 0.5);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                modelOut.set(modelComposite.scoreWithExplanation(FEATURES));
                return null;
            });
            var f2 = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                fallbackOut.set(fallbackComposite.scoreWithExplanation(FEATURES));
                return null;
            });
            start.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);

            assertThat(modelOut.get().compositeSnapshot().isolationForestScoreMode()).isEqualTo("MODEL");
            assertThat(modelOut.get().compositeSnapshot().isolationForestIncludedInBlend()).isTrue();
            assertThat(fallbackOut.get().compositeSnapshot().isolationForestScoreMode()).isEqualTo("FALLBACK_NO_MODEL");
            assertThat(fallbackOut.get().compositeSnapshot().isolationForestIncludedInBlend()).isFalse();
            assertThat(fallbackOut.get().score()).isEqualTo(0.1);
        } finally {
            pool.shutdownNow();
        }
    }
}
