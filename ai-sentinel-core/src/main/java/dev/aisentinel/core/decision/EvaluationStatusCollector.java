package dev.aisentinel.core.decision;

import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;

import java.util.EnumSet;
import java.util.Set;

/**
 * Collects {@link EvaluationStatus} from known scorer implementations without expanding {@link AnomalyScorer}.
 */
final class EvaluationStatusCollector {

    private EvaluationStatusCollector() {
    }

    static Set<EvaluationStatus> collect(AnomalyScorer scorer, RequestFeatures features) {
        EnumSet<EvaluationStatus> out = EnumSet.noneOf(EvaluationStatus.class);
        contribute(scorer, features, out);
        boolean degraded = out.contains(EvaluationStatus.STATISTICAL_WARMUP)
            || out.contains(EvaluationStatus.MODEL_UNAVAILABLE)
            || out.contains(EvaluationStatus.MODEL_FALLBACK_USED);
        if (!degraded) {
            out.add(EvaluationStatus.COMPLETE);
        }
        return Set.copyOf(out);
    }

    private static void contribute(AnomalyScorer scorer, RequestFeatures features, Set<EvaluationStatus> out) {
        if (scorer instanceof CompositeScorer composite) {
            for (AnomalyScorer child : composite.scorersView()) {
                contribute(child, features, out);
            }
            return;
        }
        if (scorer instanceof StatisticalScorer statistical) {
            if (statistical.isWarmup(features)) {
                out.add(EvaluationStatus.STATISTICAL_WARMUP);
            } else {
                out.add(EvaluationStatus.STATISTICAL_LIVE);
            }
            return;
        }
        if (scorer instanceof IsolationForestScorer isolationForest) {
            IsolationForestScorer.LastScoreMode mode = isolationForest.lastScoreMode();
            if (mode == IsolationForestScorer.LastScoreMode.FALLBACK_NO_MODEL) {
                out.add(EvaluationStatus.MODEL_UNAVAILABLE);
                out.add(EvaluationStatus.MODEL_FALLBACK_USED);
            } else if (mode == IsolationForestScorer.LastScoreMode.FALLBACK_INVALID) {
                out.add(EvaluationStatus.MODEL_FALLBACK_USED);
            }
        }
    }
}
