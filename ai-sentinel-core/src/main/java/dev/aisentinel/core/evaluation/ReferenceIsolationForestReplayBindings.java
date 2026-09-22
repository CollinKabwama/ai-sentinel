package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayEngine;
import dev.aisentinel.core.replay.ReplayPolicyConfiguration;
import dev.aisentinel.core.replay.ReplaySchemas;
import dev.aisentinel.core.replay.ReplayScorerConfiguration;
import dev.aisentinel.core.replay.ReplayDatasetLoader;

import java.util.Objects;

/**
 * Replay bindings for the offline Level-3 Isolation Forest reference scorer.
 */
final class ReferenceIsolationForestReplayBindings {

    private ReferenceIsolationForestReplayBindings() {
    }

    static ReplayConfiguration configuration(ReferenceIsolationForestConfig config) {
        ReferenceIsolationForestConfig safe = Objects.requireNonNull(config, "config");
        ReplayConfiguration defaults = ReplayConfiguration.referenceDefaults();
        return new ReplayConfiguration(
            ReplaySchemas.REPLAY_MODE_FRESH_RUN,
            defaults.aiSentinelVersion(),
            ReplayScorerConfiguration.forCandidate(
                ReferenceIsolationForestConfig.SCORER_ID,
                ReferenceIsolationForestConfig.SCORER_VERSION,
                safe.configurationDigestHex()
            ),
            ReplayPolicyConfiguration.defaultThresholds()
        );
    }

    static GeneratedCorpusDetectionEvaluator evaluator(ReferenceIsolationForestScorer scorer) {
        Objects.requireNonNull(scorer, "scorer");
        return new GeneratedCorpusDetectionEvaluator(
            new ReplayDatasetLoader(),
            new DetectionEvaluationRunner(ReplayEngine.withEvaluationScorer(scorer))
        );
    }
}
