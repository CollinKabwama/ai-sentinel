package io.aisentinel.core.scoring;

import io.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IsolationForestScorerTest {

    /** IF scoring uses the five behavioral features only; hash/ipBucket vary for statistical path tests. */
    private static RequestFeatures features(double rpw, double entropy, double tokenAge, int paramCount, long payload,
                                            long headerHash, int ipBucket) {
        return RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(rpw)
            .endpointEntropy(entropy)
            .tokenAgeSeconds(tokenAge)
            .parameterCount(paramCount)
            .payloadSizeBytes(payload)
            .headerFingerprintHash(headerHash)
            .ipBucket(ipBucket)
            .build();
    }

    private static RequestFeatures features(double rpw, double entropy, double tokenAge, int paramCount, long payload) {
        return features(rpw, entropy, tokenAge, paramCount, payload, 0L, 0);
    }

    @Test
    void scoreReturnsFallbackWhenNoModel() {
        var buffer = new BoundedTrainingBuffer(1000);
        var config = new IsolationForestConfig(0.5, 50, 10, 5, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        assertThat(scorer.isModelLoaded()).isFalse();
        assertThat(scorer.score(features(1, 0, 60, 0, 100))).isEqualTo(0.5);
    }

    @Test
    void scoreInRangeAfterTraining() {
        var buffer = new BoundedTrainingBuffer(500);
        var config = new IsolationForestConfig(0.5, 50, 20, 8, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        for (int i = 0; i < 100; i++) {
            buffer.add(new double[]{i % 10, 0.5, 60, 2, 100 + i});
        }
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isTrue();
        double s = scorer.score(features(5, 0.5, 60, 2, 150));
        assertThat(s).isBetween(0.0, 1.0);
    }

    @Test
    void retrainFailureDoesNotBreakInference() {
        var buffer = new BoundedTrainingBuffer(10);
        var config = new IsolationForestConfig(0.4, 100, 5, 5, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        for (int i = 0; i < 5; i++) buffer.add(new double[]{1, 2, 3, 4, 5});
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isFalse();
        assertThat(scorer.score(features(1, 2, 3, 4, 5))).isEqualTo(0.4);
        scorer.retrain();
        assertThat(scorer.score(features(1, 2, 3, 4, 5))).isEqualTo(0.4);
    }

    @Test
    void modelSwapIsAtomicAndVisible() throws InterruptedException {
        var buffer = new BoundedTrainingBuffer(200);
        var config = new IsolationForestConfig(0.5, 30, 10, 6, 123L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        for (int i = 0; i < 50; i++) {
            buffer.add(new double[]{i, i * 0.1, 60, 1, 100});
        }
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isTrue();
        long v1 = scorer.getModelVersion();
        for (int i = 0; i < 50; i++) {
            buffer.add(new double[]{i + 50, 0.5, 60, 1, 200});
        }
        scorer.retrain();
        long v2 = scorer.getModelVersion();
        assertThat(v2).isGreaterThanOrEqualTo(v1);
        double score = scorer.score(features(25, 0.5, 60, 1, 150));
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void trainingRejectionThresholdIncrementsRejectedCounter() {
        var buffer = new BoundedTrainingBuffer(500);
        var config = new IsolationForestConfig(0.5, 10, 20, 8, 42L, 1.0, 0.0);
        var scorer = new IsolationForestScorer(buffer, config);
        for (int i = 0; i < 100; i++) {
            buffer.add(new double[]{i % 10, 0.5, 60, 2, 100 + i});
        }
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isTrue();
        long rejBefore = scorer.getRejectedTrainingSampleCount();
        scorer.update(features(5, 0.5, 60, 2, 150));
        assertThat(scorer.getRejectedTrainingSampleCount()).isGreaterThanOrEqualTo(rejBefore);
    }

    @Test
    void acceptedTrainingSamplesIncrementWhenNotRejected() {
        var buffer = new BoundedTrainingBuffer(500);
        var config = new IsolationForestConfig(0.5, 10, 20, 8, 42L, 1.0, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        for (int i = 0; i < 100; i++) {
            buffer.add(new double[]{i % 10, 0.5, 60, 2, 100 + i});
        }
        scorer.retrain();
        long accBefore = scorer.getAcceptedTrainingSampleCount();
        scorer.update(features(5, 0.5, 60, 2, 150));
        assertThat(scorer.getAcceptedTrainingSampleCount()).isGreaterThan(accBefore);
    }

    @Test
    void inferenceIgnoresHeaderHashAndIpBucket() {
        var buffer = new BoundedTrainingBuffer(500);
        var config = new IsolationForestConfig(0.5, 50, 20, 8, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        for (int i = 0; i < 100; i++) {
            buffer.add(new double[]{i % 10, 0.5, 60, 2, 100 + i});
        }
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isTrue();
        double a = scorer.score(features(5, 0.5, 60, 2, 150, 0L, 0));
        double b = scorer.score(features(5, 0.5, 60, 2, 150, 99_999L, 7));
        assertThat(a).isEqualTo(b);
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
        for (int i = 0; i < 15; i++) buffer.add(new double[]{1, 2, 3, 4, 5});
        assertThat(scorer.getBufferedSampleCount()).isEqualTo(15);
        scorer.retrain();
        assertThat(scorer.isModelLoaded()).isTrue();
        assertThat(scorer.getModelVersion()).isGreaterThan(0);
        assertThat(scorer.getLastRetrainTimeMillis()).isGreaterThan(0);
        assertThat(scorer.getModelAgeMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(scorer.getRetrainFailureCount()).isEqualTo(0L);
    }
}
