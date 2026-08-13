package dev.aisentinel.core.baseline;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurableBaselineUpdatePolicyTest {

    @ParameterizedTest
    @CsvSource({
        "ALLOW, true",
        "MONITOR, true",
        "THROTTLE, false",
        "BLOCK, false",
        "QUARANTINE, false"
    })
    void allowOrMonitor_defaultMatrix(EnforcementAction action, boolean expected) {
        BaselineUpdatePolicy policy = ConfigurableBaselineUpdatePolicy.allowOrMonitor();
        assertThat(policy.shouldUpdate(ctx(0.5, action))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "ALLOW, true",
        "MONITOR, false",
        "THROTTLE, false",
        "BLOCK, false",
        "QUARANTINE, false"
    })
    void allowOnly_matrix(EnforcementAction action, boolean expected) {
        BaselineUpdatePolicy policy = new ConfigurableBaselineUpdatePolicy(BaselineUpdateMode.ALLOW_ONLY, 0.4);
        assertThat(policy.shouldUpdate(ctx(0.5, action))).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(EnforcementAction.class)
    void always_updatesEveryAction(EnforcementAction action) {
        BaselineUpdatePolicy policy = ConfigurableBaselineUpdatePolicy.always();
        assertThat(policy.shouldUpdate(ctx(0.99, action))).isTrue();
    }

    @Test
    void scoreBelowThreshold_usesPolicyScoreStrictlyBelow() {
        BaselineUpdatePolicy policy =
            new ConfigurableBaselineUpdatePolicy(BaselineUpdateMode.SCORE_BELOW_THRESHOLD, 0.4);
        assertThat(policy.shouldUpdate(ctx(0.399, EnforcementAction.THROTTLE))).isTrue();
        assertThat(policy.shouldUpdate(ctx(0.4, EnforcementAction.ALLOW))).isFalse();
        assertThat(policy.shouldUpdate(ctx(0.5, EnforcementAction.ALLOW))).isFalse();
    }

    @Test
    void statisticalWarmup_alwaysUpdatesEvenWhenRiskWouldThrottle() {
        BaselineUpdatePolicy policy = ConfigurableBaselineUpdatePolicy.allowOrMonitor();
        BaselineUpdateContext warmup = new BaselineUpdateContext(
            0.4, 0.4, EnforcementAction.THROTTLE, Set.of(EvaluationStatus.STATISTICAL_WARMUP));
        assertThat(policy.shouldUpdate(warmup)).isTrue();
    }

    @Test
    void emptyStatuses_immutableContext() {
        BaselineUpdateContext context = new BaselineUpdateContext(
            0.1, 0.1, EnforcementAction.ALLOW, Set.of());
        assertThat(context.evaluationStatuses()).isEmpty();
        assertThatThrownBy(() -> context.evaluationStatuses().add(EvaluationStatus.COMPLETE))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fallbackStatusAlone_doesNotForceUpdate() {
        BaselineUpdatePolicy policy = ConfigurableBaselineUpdatePolicy.allowOrMonitor();
        BaselineUpdateContext ctx = new BaselineUpdateContext(
            0.7, 0.7, EnforcementAction.BLOCK,
            Set.of(EvaluationStatus.MODEL_FALLBACK_USED, EvaluationStatus.MODEL_UNAVAILABLE));
        assertThat(policy.shouldUpdate(ctx)).isFalse();
    }

    @Test
    void rejectsInvalidThreshold() {
        assertThatThrownBy(() -> new ConfigurableBaselineUpdatePolicy(BaselineUpdateMode.ALWAYS, Double.NaN))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfigurableBaselineUpdatePolicy(BaselineUpdateMode.ALWAYS, -0.1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfigurableBaselineUpdatePolicy(BaselineUpdateMode.ALWAYS, 1.1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static BaselineUpdateContext ctx(double policyScore, EnforcementAction action) {
        return new BaselineUpdateContext(policyScore, policyScore, action, Set.of(EvaluationStatus.STATISTICAL_LIVE));
    }
}
