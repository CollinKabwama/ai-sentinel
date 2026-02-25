package io.aisentinel.core.scoring;

import io.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeScorerTest {

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
    void nanScoreReturnsOneNotBypass() {
        var composite = new CompositeScorer();
        composite.addScorer(new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return Double.NaN;
            }
            @Override
            public void update(RequestFeatures features) {}
        }, 1.0);
        assertThat(composite.score(FEATURES)).isEqualTo(1.0);
    }

    @Test
    void negativeScoreReturnsOne() {
        var composite = new CompositeScorer();
        composite.addScorer(new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) { return -0.5; }
            @Override
            public void update(RequestFeatures features) {}
        }, 1.0);
        assertThat(composite.score(FEATURES)).isEqualTo(1.0);
    }
}
