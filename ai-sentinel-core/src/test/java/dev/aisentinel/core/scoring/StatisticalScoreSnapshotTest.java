package dev.aisentinel.core.scoring;

import dev.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fidelity: <strong>scorer-only</strong> — statistical explanation captured during scoring.
 */
class StatisticalScoreSnapshotTest {

    @Test
    void warmupSnapshotExposesWarmupFlagWithoutDominantFeature() {
        var scorer = new StatisticalScorer(100, 60_000L, 999, 0.33);
        RequestFeatures f = features(10);
        assertThat(scorer.score(f)).isEqualTo(0.33);
        StatisticalScoreSnapshot snap = scorer.getLastScoreSnapshot();
        assertThat(snap).isNotNull();
        assertThat(snap.warmup()).isTrue();
        assertThat(snap.score()).isEqualTo(0.33);
        assertThat(snap.dominantFeature()).isNull();
    }

    @Test
    void liveSnapshotReportsDominantRequestsPerWindowForStep() {
        var scorer = new StatisticalScorer(100, 60_000L, 2, 0.33);
        RequestFeatures calm = features(10);
        for (int i = 0; i < 40; i++) {
            scorer.update(calm);
        }
        RequestFeatures step = features(100);
        double score = scorer.score(step);
        StatisticalScoreSnapshot snap = scorer.getLastScoreSnapshot();

        assertThat(snap.warmup()).isFalse();
        assertThat(snap.score()).isEqualTo(score);
        assertThat(snap.dominantFeature()).isEqualTo("requestsPerWindow");
        assertThat(snap.observedValue()).isEqualTo(100.0);
        assertThat(snap.referenceMean()).isCloseTo(10.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(snap.cappedAbsZ()).isPositive();
        assertThat(snap.rawAbsZ()).isGreaterThanOrEqualTo(snap.cappedAbsZ());
        assertThat(snap.effectiveStd()).isPositive();
    }

    @Test
    void dominantSignalIsDeterministicForIdenticalState() {
        var a = new StatisticalScorer(100, 60_000L, 2, 0.33);
        var b = new StatisticalScorer(100, 60_000L, 2, 0.33);
        RequestFeatures calm = features(10);
        for (int i = 0; i < 30; i++) {
            a.update(calm);
            b.update(calm);
        }
        RequestFeatures step = features(80);
        a.score(step);
        b.score(step);
        assertThat(a.getLastScoreSnapshot()).isEqualTo(b.getLastScoreSnapshot());
    }

    private static RequestFeatures features(double rpw) {
        return RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(rpw)
            .endpointEntropy(0.5)
            .endpointConcentration(0.5)
            .tokenAgeSeconds(60)
            .parameterCount(2)
            .payloadSizeBytes(100)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }
}
