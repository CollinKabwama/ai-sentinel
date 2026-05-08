package io.aisentinel.core.identity.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustEvaluationTest {

    @Test
    void nullRiskSignalsBecomeEmpty() {
        TrustEvaluation e = new TrustEvaluation(TrustScore.fullyTrusted(), null);
        assertThat(e.riskSignals().components()).isEmpty();
    }

    @Test
    void nullTrustScoreRejected() {
        assertThatThrownBy(() -> new TrustEvaluation(null, IdentityRiskSignals.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trustScore");
    }
}
