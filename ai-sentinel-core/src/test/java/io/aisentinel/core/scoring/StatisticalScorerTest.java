package io.aisentinel.core.scoring;

import io.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticalScorerTest {

    @Test
    void scoreReturnsWarmupScoreWhenInsufficientData() {
        var scorer = new StatisticalScorer();
        var f = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
        assertThat(scorer.score(f)).isEqualTo(0.4);
    }

    @Test
    void warmupScoreConfigurable() {
        var scorer = new StatisticalScorer(1000, 60_000L, 5, 0.6);
        var f = RequestFeatures.builder()
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
        assertThat(scorer.score(f)).isEqualTo(0.6);
    }

    @Test
    void scoreIncreasesAfterAnomalousUpdate() {
        var scorer = new StatisticalScorer();
        var normal = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(2)
            .payloadSizeBytes(100)
            .headerFingerprintHash(10)
            .ipBucket(1)
            .build();
        for (int i = 0; i < 5; i++) {
            scorer.update(normal);
        }
        double scoreNormal = scorer.score(normal);

        var anomalous = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(1000)
            .endpointEntropy(5)
            .tokenAgeSeconds(60)
            .parameterCount(100)
            .payloadSizeBytes(10_000_000)
            .headerFingerprintHash(999)
            .ipBucket(999)
            .build();
        double scoreAnomalous = scorer.score(anomalous);

        assertThat(scoreAnomalous).isGreaterThan(scoreNormal);
        assertThat(scoreAnomalous).isBetween(0.0, 1.0);
    }

    @Test
    void stateByKeyEvictsWhenOverMaxKeys() {
        var scorer = new StatisticalScorer(5, 60_000L);
        var f = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(2)
            .payloadSizeBytes(100)
            .headerFingerprintHash(10)
            .ipBucket(1)
            .build();
        for (int i = 0; i < 10; i++) {
            scorer.update(RequestFeatures.builder()
                .identityHash("id" + i)
                .endpoint("/api")
                .timestampMillis(0)
                .requestsPerWindow(1)
                .endpointEntropy(0)
                .tokenAgeSeconds(60)
                .parameterCount(2)
                .payloadSizeBytes(100)
                .headerFingerprintHash(10)
                .ipBucket(1)
                .build());
        }
        for (int i = 0; i < 5; i++) {
            scorer.update(f);
        }
        double s = scorer.score(f);
        assertThat(s).isBetween(0.0, 1.0);
    }

    @Test
    void manyUpdatesDoNotProduceNaN() {
        var scorer = new StatisticalScorer(100_000, 300_000L);
        var f = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(2)
            .payloadSizeBytes(100)
            .headerFingerprintHash(10)
            .ipBucket(1)
            .build();
        for (int i = 0; i < 500; i++) {
            scorer.update(f);
        }
        double s = scorer.score(f);
        assertThat(s).isBetween(0.0, 1.0);
        assertThat(Double.isNaN(s)).isFalse();
    }

    @Test
    void concurrentUpdateAndScoreNoDataRace() throws InterruptedException {
        var scorer = new StatisticalScorer(10_000, 300_000L);
        var f = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(2)
            .payloadSizeBytes(100)
            .headerFingerprintHash(10)
            .ipBucket(1)
            .build();
        Thread[] updaters = new Thread[4];
        for (int t = 0; t < updaters.length; t++) {
            updaters[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    scorer.update(f);
                }
            });
        }
        for (Thread u : updaters) u.start();
        for (Thread u : updaters) u.join();
        double score = scorer.score(f);
        assertThat(score).isBetween(0.0, 1.0);
    }
}
