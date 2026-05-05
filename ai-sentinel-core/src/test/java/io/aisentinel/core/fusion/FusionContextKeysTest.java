package io.aisentinel.core.fusion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FusionContextKeysTest {

    @Test
    void fusedRiskKeyIsStable() {
        assertThat(FusionContextKeys.FUSED_RISK).isEqualTo("io.aisentinel.fusion.FUSED_RISK");
    }
}
