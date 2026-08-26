package dev.aisentinel.core.scoring;

import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositeScorerAllInvalidTest {

    private static final RequestFeatures FEATURES = RequestFeatures.builder()
        .identityHash("id")
        .endpoint("/api")
        .timestampMillis(0)
        .requestsPerWindow(1)
        .endpointEntropy(0)
        .tokenAgeSeconds(60)
        .parameterCount(0)
        .payloadSizeBytes(100)
        .headerFingerprintHash(0)
        .ipBucket(0)
        .build();

    private static final class FixedScorer implements AnomalyScorer {
        private final double score;

        private FixedScorer(double score) {
            this.score = score;
        }

        @Override
        public double score(RequestFeatures features) {
            return score;
        }

        @Override
        public void update(RequestFeatures features) {
        }
    }

    private static IsolationForestScorer ifScorerWithModelReturning(double modelScore) throws Exception {
        var buffer = new BoundedTrainingBuffer(10);
        var config = new IsolationForestConfig(0.42, 50, 10, 5, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        IsolationForestModel model = mock(IsolationForestModel.class);
        when(model.score(any(double[].class))).thenReturn(modelScore);
        var modelField = IsolationForestScorer.class.getDeclaredField("model");
        modelField.setAccessible(true);
        modelField.set(scorer, model);
        return scorer;
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.7})
    void ifOnlyInvalidCompositePropagatesInvalidInsteadOfZero(double modelScore) throws Exception {
        var composite = new CompositeScorer();
        composite.addScorer(ifScorerWithModelReturning(modelScore), 1.0);

        double out = composite.score(FEATURES);

        if (Double.isNaN(modelScore)) {
            assertThat(Double.isNaN(out)).isTrue();
        } else {
            assertThat(out).isEqualTo(modelScore);
        }
        assertThat(out).isNotEqualTo(0.0);
        assertThat(out).isNotEqualTo(0.5);
        assertThat(out).isNotEqualTo(1.0);
        var snap = composite.getLastCompositeScoreSnapshot();
        assertThat(snap).isNotNull();
        assertThat(snap.isolationForestIncludedInBlend()).isFalse();
        assertThat(snap.isolationForestScoreMode()).isEqualTo("FALLBACK_INVALID");
    }

    @Test
    void allInvalidMultipleContributorsPropagateInvalid() {
        var composite = new CompositeScorer();
        composite.addScorer(new FixedScorer(Double.NaN), 1.0);
        composite.addScorer(new FixedScorer(Double.POSITIVE_INFINITY), 1.0);
        composite.addScorer(new FixedScorer(-0.1), 1.0);

        assertThat(Double.isNaN(composite.score(FEATURES))).isTrue();
    }

    @Test
    void mixedValidAndInvalidUsesOnlyValidContributors() {
        var composite = new CompositeScorer();
        composite.addScorer(new FixedScorer(Double.NaN), 1.0);
        composite.addScorer(new FixedScorer(0.3), 1.0);
        composite.addScorer(new FixedScorer(Double.POSITIVE_INFINITY), 1.0);
        composite.addScorer(new FixedScorer(0.7), 1.0);

        assertThat(composite.score(FEATURES)).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1.0e-12));
    }

    @Test
    void invalidStatisticalAndValidIsolationForestUsesIsolationForestOnly() throws Exception {
        var composite = new CompositeScorer();
        composite.addScorer(new FixedScorer(Double.NaN), 1.0);
        composite.addScorer(ifScorerWithModelReturning(0.6), 1.0);

        assertThat(composite.score(FEATURES)).isEqualTo(0.6);
        assertThat(composite.getLastCompositeScoreSnapshot().isolationForestIncludedInBlend()).isTrue();
    }

    @Test
    void validStatisticalAndInvalidIsolationForestUsesStatisticalOnly() throws Exception {
        var composite = new CompositeScorer();
        composite.addScorer(new StatisticalScorer(100, 60_000L, 999, 0.3), 1.0);
        composite.addScorer(ifScorerWithModelReturning(Double.POSITIVE_INFINITY), 1.0);

        assertThat(composite.score(FEATURES)).isEqualTo(0.3);
        assertThat(composite.getLastCompositeScoreSnapshot().isolationForestIncludedInBlend()).isFalse();
    }

    @Test
    void validOnlyCompositePreserved() {
        var composite = new CompositeScorer();
        composite.addScorer(new FixedScorer(0.2), 1.0);
        composite.addScorer(new FixedScorer(0.6), 3.0);

        assertThat(composite.score(FEATURES)).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1.0e-12));
    }

    @Test
    void finiteAboveOneStillRangeClampsAfterValidAggregation() {
        var composite = new CompositeScorer();
        composite.addScorer(new FixedScorer(1.5), 1.0);

        assertThat(composite.score(FEATURES)).isEqualTo(1.0);
    }

    @Test
    void emptyCompositeStillReturnsZeroWithoutInvalidStatusCandidate() {
        AtomicInteger compositeScores = new AtomicInteger();
        var composite = new CompositeScorer(new SentinelMetrics() {
            @Override
            public void recordCompositeScore(double score) {
                compositeScores.incrementAndGet();
            }
        });

        assertThat(composite.score(FEATURES)).isEqualTo(0.0);
        assertThat(composite.getLastCompositeScoreSnapshot()).isNull();
        assertThat(compositeScores.get()).isEqualTo(1);
    }
}
