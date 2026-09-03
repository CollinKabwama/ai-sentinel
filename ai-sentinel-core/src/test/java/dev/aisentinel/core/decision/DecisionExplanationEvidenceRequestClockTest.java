package dev.aisentinel.core.decision;

import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionExplanationEvidenceRequestClockTest {

    @Test
    void statisticalEvidenceUsesFeatureTimestamp() {
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/e")
            .timestampMillis(1_700_000_123_000L)
            .requestsPerWindow(1)
            .build();
        StatisticalScorer scorer = new StatisticalScorer();
        var outcome = scorer.scoreWithExplanation(features);
        DecisionExplanationEvidence evidence = DecisionExplanationEvidence.fromStatistical(
            outcome.score(), outcome.snapshot(), features.effectiveTimestampMillis());
        assertThat(evidence.scoredAtEpochMillis()).isEqualTo(1_700_000_123_000L);
    }
}
