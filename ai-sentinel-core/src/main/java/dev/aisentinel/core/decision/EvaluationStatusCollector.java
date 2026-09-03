package dev.aisentinel.core.decision;

import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Set;

/**
 * Collects {@link EvaluationStatus} via {@link EvaluationStatusContributor} when available.
 * Components that do not implement the SPI contribute no type-specific markers (COMPLETE only
 * when nothing degraded was reported).
 */
final class EvaluationStatusCollector {

    private static final Logger log = LoggerFactory.getLogger(EvaluationStatusCollector.class);

    private EvaluationStatusCollector() {
    }

    static Set<EvaluationStatus> collect(AnomalyScorer scorer, RequestFeatures features) {
        return collect(scorer, features, null);
    }

    /**
     * @param isolationForestModeOrNull request-owned IF mode from the same score invocation; when non-null,
     *                                  used instead of reading the scorer's shared {@code lastScoreMode}
     */
    static Set<EvaluationStatus> collect(AnomalyScorer scorer,
                                         RequestFeatures features,
                                         IsolationForestScorer.LastScoreMode isolationForestModeOrNull) {
        EnumSet<EvaluationStatus> out = EnumSet.noneOf(EvaluationStatus.class);
        contribute(scorer, features, isolationForestModeOrNull, out);
        boolean degraded = out.contains(EvaluationStatus.STATISTICAL_WARMUP)
            || out.contains(EvaluationStatus.MODEL_UNAVAILABLE)
            || out.contains(EvaluationStatus.MODEL_FALLBACK_USED)
            || out.contains(EvaluationStatus.DEGRADED);
        if (!degraded) {
            out.add(EvaluationStatus.COMPLETE);
        }
        return Set.copyOf(out);
    }

    private static void contribute(AnomalyScorer scorer,
                                   RequestFeatures features,
                                   IsolationForestScorer.LastScoreMode isolationForestModeOrNull,
                                   Set<EvaluationStatus> out) {
        if (scorer instanceof CompositeScorer composite) {
            for (AnomalyScorer child : composite.scorersView()) {
                contribute(child, features, isolationForestModeOrNull, out);
            }
            return;
        }
        if (!(scorer instanceof EvaluationStatusContributor contributor)) {
            return;
        }
        String modeName = isolationForestModeOrNull != null ? isolationForestModeOrNull.name() : null;
        boolean trustedLifecycleContributor = scorer instanceof StatisticalScorer;
        EvaluationStatusContributionContext ctx =
            new EvaluationStatusContributionContext(out, modeName, trustedLifecycleContributor);
        try {
            contributor.contributeEvaluationStatuses(features, ctx);
        } catch (RuntimeException e) {
            log.warn("Evaluation status contributor failed; treating as degraded: {}", e.toString());
            out.add(EvaluationStatus.DEGRADED);
        }
    }
}
