package dev.aisentinel.core.scoring;

import dev.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fidelity: <strong>scorer-only</strong> — composite weighting semantics for IF model vs fallback.
 */
class CompositeScorerIsolationForestBlendTest {

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
    void statisticalOnly_compositeEqualsStatistical() {
        var composite = new CompositeScorer();
        composite.addScorer(constant(0.1), 1.0);
        assertThat(composite.score(FEATURES)).isEqualTo(0.1);
        var snap = composite.getLastCompositeScoreSnapshot();
        assertThat(snap.isolationForest()).isNull();
        assertThat(snap.isolationForestIncludedInBlend()).isFalse();
    }

    @Test
    void isolationForestEnabledButNoModel_fallbackVisibleButExcludedFromBlend() {
        var stat = new StatisticalScorer(100, 60_000L, 999, 0.1);
        var ifScorer = new IsolationForestScorer(
            new BoundedTrainingBuffer(10),
            new IsolationForestConfig(0.5, 100, 5, 5, 42L, 1.0)
        );
        var composite = new CompositeScorer();
        composite.addScorer(stat, 1.0);
        composite.addScorer(ifScorer, 0.5);

        double out = composite.score(FEATURES);
        var snap = composite.getLastCompositeScoreSnapshot();

        assertThat(ifScorer.lastScoreMode()).isEqualTo(IsolationForestScorer.LastScoreMode.FALLBACK_NO_MODEL);
        assertThat(snap.statistical()).isEqualTo(0.1);
        assertThat(snap.isolationForest()).isEqualTo(0.5);
        assertThat(snap.isolationForestIncludedInBlend()).isFalse();
        assertThat(snap.isolationForestScoreMode()).isEqualTo("FALLBACK_NO_MODEL");
        assertThat(out).isEqualTo(0.1);
        assertThat(snap.composite()).isEqualTo(0.1);
    }

    @Test
    void isolationForestLoaded_modelScoreIncludedInWeightedBlend() {
        var buffer = new BoundedTrainingBuffer(500);
        for (int i = 0; i < 100; i++) {
            buffer.add(new double[] {i % 10, 0.5, 60, 2, 100 + i});
        }
        var ifScorer = new IsolationForestScorer(
            buffer,
            new IsolationForestConfig(0.5, 50, 20, 8, 42L, 1.0)
        );
        ifScorer.retrain();
        assertThat(ifScorer.isModelLoaded()).isTrue();

        double ifScore = ifScorer.score(FEATURES);
        assertThat(ifScorer.lastScoreMode()).isEqualTo(IsolationForestScorer.LastScoreMode.MODEL);
        assertThat(ifScore).isNotEqualTo(0.5);

        var composite = new CompositeScorer();
        composite.addScorer(constant(0.1), 1.0);
        composite.addScorer(ifScorer, 0.5);

        double expected = (0.1 * 1.0 + ifScore * 0.5) / 1.5;
        double out = composite.score(FEATURES);
        var snap = composite.getLastCompositeScoreSnapshot();

        assertThat(snap.isolationForestIncludedInBlend()).isTrue();
        assertThat(snap.isolationForestScoreMode()).isEqualTo("MODEL");
        assertThat(snap.isolationForest()).isEqualTo(ifScore);
        assertThat(out).isEqualTo(expected);
        assertThat(snap.composite()).isEqualTo(expected);
    }

    @Test
    void disabledIsolationForest_notRegistered_noIfComponent() {
        var composite = new CompositeScorer();
        composite.addScorer(constant(0.22), 1.0);
        composite.score(FEATURES);
        var snap = composite.getLastCompositeScoreSnapshot();
        assertThat(snap.isolationForest()).isNull();
        assertThat(snap.isolationForestScoreMode()).isNull();
        assertThat(snap.isolationForestIncludedInBlend()).isFalse();
        assertThat(snap.composite()).isEqualTo(0.22);
    }

    private static AnomalyScorer constant(double value) {
        return new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return value;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
    }
}
