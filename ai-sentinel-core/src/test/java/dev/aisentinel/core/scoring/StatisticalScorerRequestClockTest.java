package dev.aisentinel.core.scoring;

import dev.aisentinel.core.model.RequestFeatures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticalScorerRequestClockTest {

    @Test
    void scoreUsesFeatureTimestampForIdleExpiry() {
        StatisticalScorer scorer = new StatisticalScorer(100, 60_000L, 1, 0.4);
        long base = 1_700_000_000_000L;
        RequestFeatures first = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(base)
            .requestsPerWindow(1)
            .build();
        scorer.update(first);
        scorer.score(first);
        assertThat(scorer.metricsStateEntryCount()).isEqualTo(1);

        RequestFeatures later = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(base + 120_000L)
            .requestsPerWindow(1)
            .build();
        scorer.score(later);
        assertThat(scorer.metricsStateEntryCount()).isZero();
    }
}
