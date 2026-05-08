package io.aisentinel.core.identity.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrustScoreTest {

    @Test
    void fullyTrustedIsOne() {
        TrustScore t = TrustScore.fullyTrusted();
        assertThat(t.value()).isEqualTo(1.0);
        assertThat(t.reason()).isEqualTo("baseline");
    }

    @Test
    void clampsToUnitInterval() {
        assertThat(new TrustScore(-0.1, "x").value()).isZero();
        assertThat(new TrustScore(1.5, "x").value()).isEqualTo(1.0);
    }

    @Test
    void nanBecomesZero() {
        assertThat(new TrustScore(Double.NaN, "x").value()).isZero();
    }

    @Test
    void nullReasonBecomesEmpty() {
        assertThat(new TrustScore(0.5, null).reason()).isEmpty();
    }
}
