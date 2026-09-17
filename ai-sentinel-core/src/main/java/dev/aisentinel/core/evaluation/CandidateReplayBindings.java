package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.replay.ReplayConfiguration;
import dev.aisentinel.core.replay.ReplayPolicyConfiguration;
import dev.aisentinel.core.replay.ReplaySchemas;
import dev.aisentinel.core.replay.ReplayScorerConfiguration;
import dev.aisentinel.core.scoring.artifact.CandidateScorerProvenance;

import java.util.Objects;

/**
 * Deterministic replay configuration bound to a loaded candidate's provenance.
 */
final class CandidateReplayBindings {

    private CandidateReplayBindings() {
    }

    static ReplayConfiguration configuration(CandidateScorerProvenance provenance) {
        CandidateScorerProvenance safe = Objects.requireNonNull(provenance, "provenance");
        if (!safe.artifactBytesVerified() || safe.verifiedDigestHex().isBlank()) {
            throw new IllegalArgumentException("candidate replay requires verified artifact provenance");
        }
        ReplayConfiguration defaults = ReplayConfiguration.referenceDefaults();
        return new ReplayConfiguration(
            ReplaySchemas.REPLAY_MODE_FRESH_RUN,
            defaults.aiSentinelVersion(),
            ReplayScorerConfiguration.forCandidate(
                safe.scorerId(),
                safe.scorerVersion(),
                safe.verifiedDigestHex()
            ),
            ReplayPolicyConfiguration.defaultThresholds()
        );
    }
}
