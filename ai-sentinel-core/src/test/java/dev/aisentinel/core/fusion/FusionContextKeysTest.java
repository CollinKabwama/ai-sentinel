package dev.aisentinel.core.fusion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FusionContextKeysTest {

    @Test
    void fusedRiskKeyIsStable() {
        assertThat(FusionContextKeys.FUSED_RISK).isEqualTo("dev.aisentinel.fusion.FUSED_RISK");
    }
}
