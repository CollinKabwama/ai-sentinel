package dev.aisentinel.core.decision;

import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.StatisticalScoreSnapshot;

/**
 * Immutable, request-owned scoring explanation captured during the same evaluation that produced
 * the {@link RiskDecision}. Stored on {@link dev.aisentinel.core.model.RequestContext} — never read
 * back from shared scorer "last snapshot" fields.
 */
public record DecisionExplanationEvidence(
    Double statisticalScore,
    Double isolationForestScore,
    Boolean isolationForestIncludedInBlend,
    String isolationForestScoreMode,
    StatisticalScoreSnapshot statisticalExplanation,
    long scoredAtEpochMillis
) {
    public static DecisionExplanationEvidence fromComposite(CompositeScorer.CompositeScoreOutcome outcome) {
        if (outcome == null) {
            return null;
        }
        CompositeScorer.CompositeScoreSnapshot snap = outcome.compositeSnapshot();
        if (snap == null) {
            if (outcome.statisticalSnapshot() != null) {
                return fromStatistical(outcome.score(), outcome.statisticalSnapshot());
            }
            return null;
        }
        double st = snap.statistical();
        return new DecisionExplanationEvidence(
            Double.isNaN(st) ? null : st,
            snap.isolationForest(),
            snap.isolationForestIncludedInBlend(),
            snap.isolationForestScoreMode(),
            outcome.statisticalSnapshot(),
            snap.evaluatedAtEpochMillis()
        );
    }

    public static DecisionExplanationEvidence fromStatistical(double score, StatisticalScoreSnapshot snapshot) {
        return new DecisionExplanationEvidence(
            score,
            null,
            false,
            null,
            snapshot,
            System.currentTimeMillis()
        );
    }
}
