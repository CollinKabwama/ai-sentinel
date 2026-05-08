package io.aisentinel.core.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustPolicyConfigTest {

    @Test
    void disabledFactoryHasExpectedThresholds() {
        TrustPolicyConfig c = TrustPolicyConfig.disabled();
        assertThat(c.enabled()).isFalse();
        assertThat(c.trustLowBandMinimum()).isEqualTo(0.25);
        assertThat(c.trustMediumBandMinimum()).isEqualTo(0.50);
        assertThat(c.trustNoEffectMinimum()).isEqualTo(0.80);
    }

    @Test
    void invalidThresholdOrderRejected() {
        assertThatThrownBy(() -> new TrustPolicyConfig(
            true,
            false,
            List.of(),
            Set.of("GET"),
            0.80,
            0.50,
            0.55,
            false,
            true,
            0.40
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trustLowBandMinimum");
    }

    @Test
    void httpMethodsNormalizedToUpperCase() {
        TrustPolicyConfig c = new TrustPolicyConfig(
            true,
            false,
            List.of(),
            Set.of("get", "Post"),
            0.80,
            0.50,
            0.25,
            false,
            true,
            0.40
        );
        assertThat(c.httpMethodsUpper()).containsExactlyInAnyOrder("GET", "POST");
    }
}
