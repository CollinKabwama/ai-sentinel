package dev.aisentinel.core.baseline;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;

import java.util.Objects;

/**
 * Mode-driven {@link BaselineUpdatePolicy}.
 * <p>
 * Statistical warmup always learns so cold-start baselines can leave warmup under gated modes.
 * Learning uses the risk-derived action (and policy score for threshold mode), not operational
 * enforcement overrides such as startup grace or quarantine presentation.
 */
public final class ConfigurableBaselineUpdatePolicy implements BaselineUpdatePolicy {

    private final BaselineUpdateMode mode;
    private final double scoreThreshold;

    public ConfigurableBaselineUpdatePolicy(BaselineUpdateMode mode, double scoreThreshold) {
        this.mode = Objects.requireNonNull(mode, "mode");
        if (Double.isNaN(scoreThreshold) || scoreThreshold < 0.0 || scoreThreshold > 1.0) {
            throw new IllegalArgumentException("scoreThreshold must be in [0, 1], got " + scoreThreshold);
        }
        this.scoreThreshold = scoreThreshold;
    }

    public static ConfigurableBaselineUpdatePolicy allowOrMonitor() {
        return new ConfigurableBaselineUpdatePolicy(BaselineUpdateMode.ALLOW_OR_MONITOR, 0.4);
    }

    public static ConfigurableBaselineUpdatePolicy always() {
        return new ConfigurableBaselineUpdatePolicy(BaselineUpdateMode.ALWAYS, 0.4);
    }

    public BaselineUpdateMode mode() {
        return mode;
    }

    public double scoreThreshold() {
        return scoreThreshold;
    }

    @Override
    public boolean shouldUpdate(BaselineUpdateContext context) {
        Objects.requireNonNull(context, "context");
        if (context.evaluationStatuses().contains(EvaluationStatus.STATISTICAL_WARMUP)) {
            return true;
        }
        return switch (mode) {
            case ALWAYS -> true;
            case ALLOW_ONLY -> context.riskAction() == EnforcementAction.ALLOW;
            case ALLOW_OR_MONITOR -> context.riskAction() == EnforcementAction.ALLOW
                || context.riskAction() == EnforcementAction.MONITOR;
            case SCORE_BELOW_THRESHOLD -> context.policyScore() < scoreThreshold;
        };
    }
}
