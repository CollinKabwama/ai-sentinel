package dev.aisentinel.core.decision;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskExplanationModelInvariantTest {

    @Test
    void danglingAdviceLinkIsRejected() {
        RiskFactor present = factor(RiskFactorCode.INVALID_SCORE_SIGNAL, RiskFactorCategory.MODEL,
            RiskFactorSeverity.HIGH, 1.0);
        SecurityAdvice advice = new SecurityAdvice(
            AdvisoryCode.REVIEW_SCORER_HEALTH,
            AdvisoryPriority.HIGH,
            "review",
            List.of(RiskFactorCode.VELOCITY_ANOMALY),
            true);

        assertThatThrownBy(() -> new RiskExplanation(List.of(present), advice))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("linkedFactorCodes");
    }

    @Test
    void duplicateDirectFactorsAreRejected() {
        RiskFactor lower = factor(RiskFactorCode.VELOCITY_ANOMALY, RiskFactorCategory.BEHAVIOR,
            RiskFactorSeverity.MEDIUM, 0.5);
        RiskFactor higher = factor(RiskFactorCode.VELOCITY_ANOMALY, RiskFactorCategory.BEHAVIOR,
            RiskFactorSeverity.HIGH, 0.7);

        assertThatThrownBy(() -> new RiskExplanation(List.of(lower, higher), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate risk factor code");
    }

    @Test
    void duplicateAdviceLinksAreRejected() {
        assertThatThrownBy(() -> new SecurityAdvice(
            AdvisoryCode.INVESTIGATE,
            AdvisoryPriority.MEDIUM,
            "duplicate",
            List.of(RiskFactorCode.VELOCITY_ANOMALY, RiskFactorCode.VELOCITY_ANOMALY),
            true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate linked factor code");
    }

    @Test
    void factorsAreImmutableAndDeterministicallyOrdered() {
        RiskFactor low = factor(RiskFactorCode.IDENTITY_NEW_SESSION, RiskFactorCategory.IDENTITY,
            RiskFactorSeverity.LOW, 0.2);
        RiskFactor high = factor(RiskFactorCode.VELOCITY_ANOMALY, RiskFactorCategory.BEHAVIOR,
            RiskFactorSeverity.HIGH, 0.7);
        List<RiskFactor> mutable = new ArrayList<>();
        mutable.add(low);
        mutable.add(high);

        RiskExplanation explanation = new RiskExplanation(mutable, null);
        mutable.clear();

        assertThat(explanation.factors()).extracting(RiskFactor::code)
            .containsExactly(RiskFactorCode.VELOCITY_ANOMALY, RiskFactorCode.IDENTITY_NEW_SESSION);
        assertThatThrownBy(() -> explanation.factors().add(low))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private static RiskFactor factor(RiskFactorCode code, RiskFactorCategory category,
                                     RiskFactorSeverity severity, double contribution) {
        return new RiskFactor(code, category, severity, contribution, 0.8,
            code.name(), code.name(), "test");
    }
}
