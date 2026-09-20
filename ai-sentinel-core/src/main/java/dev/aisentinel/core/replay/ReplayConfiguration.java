package dev.aisentinel.core.replay;

import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

/**
 * Deterministic replay configuration.
 */
public record ReplayConfiguration(
    String replayMode,
    String aiSentinelVersion,
    ReplayScorerConfiguration scorer,
    ReplayPolicyConfiguration policy
) {
    public ReplayConfiguration {
        replayMode = ReplaySchemas.requireSupportedReplayMode(replayMode == null ? "" : replayMode);
        if (aiSentinelVersion == null || aiSentinelVersion.isBlank()) {
            throw new IllegalArgumentException("aiSentinelVersion is required");
        }
        if (scorer == null) {
            throw new IllegalArgumentException("scorer is required");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy is required");
        }
    }

    public static ReplayConfiguration referenceDefaults() {
        return new ReplayConfiguration(
            ReplaySchemas.REPLAY_MODE_FRESH_RUN,
            "0.3.0",
            ReplayScorerConfiguration.statisticalDefaults(),
            ReplayPolicyConfiguration.defaultThresholds()
        );
    }

    public String configurationFingerprint() {
        String material = replayMode
            + "|ai=" + aiSentinelVersion
            + "|scorerKind=" + scorer.scorerKind()
            + "|scorerId=" + scorer.scorerId()
            + "|scorerVersion=" + scorer.scorerVersion()
            + "|maxKeys=" + scorer.maxKeys()
            + "|ttlMs=" + scorer.ttlMs()
            + "|warmupMinSamples=" + scorer.warmupMinSamples()
            + "|warmupScore=" + Double.toString(scorer.warmupScore())
            + "|policyId=" + policy.policyId()
            + "|policyVersion=" + policy.policyVersion()
            + "|evaluationMode=" + policy.evaluationMode()
            + "|moderateThreshold=" + Double.toString(policy.moderateThreshold())
            + "|elevatedThreshold=" + Double.toString(policy.elevatedThreshold())
            + "|highThreshold=" + Double.toString(policy.highThreshold())
            + "|criticalThreshold=" + Double.toString(policy.criticalThreshold());
        return TrainingFingerprintHashes.sha256HexUtf8(material);
    }
}
