package io.aisentinel.core.scoring;

import io.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IsolationForestScorerTest {

    private static RequestFeatures features(double... values) {
        if (values.length < 7) throw new IllegalArgumentException("need 7 values");
        return RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(values[0])
            .endpointEntropy(values[1])
            .tokenAgeSeconds(values[2])
            .parameterCount((int) values[3])
            .payloadSizeBytes((long) values[4])
            .headerFingerprintHash((long) values[5])
            .ipBucket((int) values[6])
            .build();
    }

    @Test
    void scoreReturnsFallbackWhenNoModel() {
        var buffer = new BoundedTrainingBuffer(1000);
        var config = new IsolationForestConfig(0.5, 50, 10, 5, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        assertThat(scorer.isModelLoaded()).isFalse();
        assertThat(scorer.score(features(1, 0, 60, 0, 100, 0, 0))).isEqualTo(0.5);
    }

    @Test
    void scoreInRangeAfterTraining() {
        var buffer = new BoundedTrainingBuffer(500);
        var config = new IsolationForestConfig(0.5, 50, 20, 8, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        for (int i = 0; i < 100; i++) {
            buffer.add(new double[]{i % 10, 0.5, 60, 2, 100 + i, 0, 0});
        }
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isTrue();
        double s = scorer.score(features(5, 0.5, 60, 2, 150, 0, 0));
        assertThat(s).isBetween(0.0, 1.0);
    }

    @Test
    void retrainFailureDoesNotBreakInference() {
        var buffer = new BoundedTrainingBuffer(10);
        var config = new IsolationForestConfig(0.4, 100, 5, 5, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        for (int i = 0; i < 5; i++) buffer.add(new double[]{1, 2, 3, 4, 5, 6, 7});
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isFalse();
        assertThat(scorer.score(features(1, 2, 3, 4, 5, 6, 7))).isEqualTo(0.4);
        scorer.retrain();
        assertThat(scorer.score(features(1, 2, 3, 4, 5, 6, 7))).isEqualTo(0.4);
    }

    @Test
    void modelSwapIsAtomicAndVisible() throws InterruptedException {
        var buffer = new BoundedTrainingBuffer(200);
        var config = new IsolationForestConfig(0.5, 30, 10, 6, 123L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        for (int i = 0; i < 50; i++) {
            buffer.add(new double[]{i, i * 0.1, 60, 1, 100, 0, 0});
        }
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isTrue();
        long v1 = scorer.getModelVersion();
        for (int i = 0; i < 50; i++) {
            buffer.add(new double[]{i + 50, 0.5, 60, 1, 200, 0, 0});
        }
        scorer.retrain();
        long v2 = scorer.getModelVersion();
        assertThat(v2).isGreaterThanOrEqualTo(v1);
        double score = scorer.score(features(25, 0.5, 60, 1, 150, 0, 0));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void metadataExposed() {
        var buffer = new BoundedTrainingBuffer(100);
        var config = new IsolationForestConfig(0.5, 10, 5, 5, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        assertThat(scorer.getBufferedSampleCount()).isEqualTo(0);
        assertThat(scorer.getLastRetrainTimeMillis()).isEqualTo(0);
        assertThat(scorer.getModelAgeMillis()).isEqualTo(-1L);
        assertThat(scorer.getRetrainFailureCount()).isEqualTo(0L);
        for (int i = 0; i < 15; i++) buffer.add(new double[]{1, 2, 3, 4, 5, 6, 7});
        assertThat(scorer.getBufferedSampleCount()).isEqualTo(15);
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isTrue();
        assertThat(scorer.getModelVersion()).isGreaterThan(0);
        assertThat(scorer.getLastRetrainTimeMillis()).isGreaterThan(0);
        assertThat(scorer.getModelAgeMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(scorer.getRetrainFailureCount()).isEqualTo(0L);
    }
}
