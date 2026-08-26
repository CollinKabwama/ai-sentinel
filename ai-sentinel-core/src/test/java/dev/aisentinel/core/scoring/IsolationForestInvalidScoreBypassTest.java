package dev.aisentinel.core.scoring;

import dev.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Independent-review P0 regression: a loaded Isolation Forest model returning a non-finite
 * or negative score must never be laundered into a valid-looking risk score (especially
 * {@code +Infinity → 1.0 → MODEL}). Invalid model output propagates raw with mode
 * {@link IsolationForestScorer.LastScoreMode#FALLBACK_INVALID} so the decision engine's
 * authoritative boundary classifies it as {@code INVALID_SCORE}.
 */
class IsolationForestInvalidScoreBypassTest {

    private static RequestFeatures features() {
        return RequestFeatures.builder()
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
    }

    private static IsolationForestScorer scorerWithModelReturning(double modelScore) throws Exception {
        var buffer = new BoundedTrainingBuffer(10);
        var config = new IsolationForestConfig(0.42, 50, 10, 5, 42L, 1.0);
        var scorer = new IsolationForestScorer(buffer, config);
        IsolationForestModel broken = mock(IsolationForestModel.class);
        when(broken.score(any(double[].class))).thenReturn(modelScore);
        var modelField = IsolationForestScorer.class.getDeclaredField("model");
        modelField.setAccessible(true);
        modelField.set(scorer, broken);
        return scorer;
    }

    @Test
    void positiveInfinityIsNotLaunderedToOne() throws Exception {
        var outcome = scorerWithModelReturning(Double.POSITIVE_INFINITY).scoreWithMode(features());

        assertThat(outcome.mode()).isEqualTo(IsolationForestScorer.LastScoreMode.FALLBACK_INVALID);
        assertThat(outcome.score()).isNotEqualTo(1.0);
        assertThat(Double.isInfinite(outcome.score())).isTrue();
    }

    @Test
    void negativeInfinityPropagatesAsInvalid() throws Exception {
        var outcome = scorerWithModelReturning(Double.NEGATIVE_INFINITY).scoreWithMode(features());

        assertThat(outcome.mode()).isEqualTo(IsolationForestScorer.LastScoreMode.FALLBACK_INVALID);
        assertThat(Double.isInfinite(outcome.score())).isTrue();
        assertThat(outcome.score()).isLessThan(0.0);
    }

    @Test
    void nanPropagatesAsInvalid() throws Exception {
        var outcome = scorerWithModelReturning(Double.NaN).scoreWithMode(features());

        assertThat(outcome.mode()).isEqualTo(IsolationForestScorer.LastScoreMode.FALLBACK_INVALID);
        assertThat(Double.isNaN(outcome.score())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, -0.7})
    void negativeFinitePropagatesAsInvalid(double modelScore) throws Exception {
        var outcome = scorerWithModelReturning(modelScore).scoreWithMode(features());

        assertThat(outcome.mode()).isEqualTo(IsolationForestScorer.LastScoreMode.FALLBACK_INVALID);
        assertThat(outcome.score()).isEqualTo(modelScore);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.25, 0.5, 0.99, 1.0})
    void validModelScoresPassThroughWithModelMode(double modelScore) throws Exception {
        var outcome = scorerWithModelReturning(modelScore).scoreWithMode(features());

        assertThat(outcome.mode()).isEqualTo(IsolationForestScorer.LastScoreMode.MODEL);
        assertThat(outcome.score()).isEqualTo(modelScore);
    }

    @Test
    void finiteAboveOneRemainsRangeClampedAsValidModelScore() throws Exception {
        // Finite >1 stays a valid range-clamp (global Increment-1 decision), distinct from non-finite.
        var outcome = scorerWithModelReturning(1.5).scoreWithMode(features());

        assertThat(outcome.mode()).isEqualTo(IsolationForestScorer.LastScoreMode.MODEL);
        assertThat(outcome.score()).isEqualTo(1.0);
    }

    @Test
    void compositeExcludesInvalidIsolationForestFromBlend() throws Exception {
        var stat = new StatisticalScorer(100, 60_000L, 999, 0.33);
        var composite = new CompositeScorer();
        composite.addScorer(stat, 1.0);
        composite.addScorer(scorerWithModelReturning(Double.POSITIVE_INFINITY), 0.5);

        // Invalid IF output must not contaminate the composite: blend uses statistical only.
        assertThat(composite.score(features())).isEqualTo(0.33);
        var snap = composite.getLastCompositeScoreSnapshot();
        assertThat(snap.isolationForestIncludedInBlend()).isFalse();
        assertThat(snap.isolationForestScoreMode()).isEqualTo("FALLBACK_INVALID");
    }
}
