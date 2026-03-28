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

    @Test
    void lastSnapshotCapturesStatisticalAndIsolationForestComponents() {
        var stat = new StatisticalScorer(100, 60_000L, 999, 0.33);
        var ifScorer = new IsolationForestScorer(
            new BoundedTrainingBuffer(10),
            new IsolationForestConfig(0.44, 100, 5, 5, 42L, 1.0)
        );
        var composite = new CompositeScorer();
        composite.addScorer(stat, 1.0);
        composite.addScorer(ifScorer, 0.5);
        double blended = composite.score(FEATURES);
        CompositeScorer.CompositeScoreSnapshot snap = composite.getLastCompositeScoreSnapshot();
        assertThat(snap).isNotNull();
        assertThat(snap.statistical()).isEqualTo(0.33);
        assertThat(snap.isolationForest()).isEqualTo(0.44);
        assertThat(snap.composite()).isEqualTo(blended);
        assertThat(snap.evaluatedAtEpochMillis()).isPositive();
    }
}
