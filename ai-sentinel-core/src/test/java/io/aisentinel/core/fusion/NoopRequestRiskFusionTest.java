package io.aisentinel.core.fusion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoopRequestRiskFusionTest {

    @Test
    void disabledFusionPassesThroughAnomalyScore() {
        assertThat(NoopRequestRiskFusion.INSTANCE.enabled()).isFalse();
        FusedRisk fused = NoopRequestRiskFusion.INSTANCE.fuse(0.55, 0.9);
        assertThat(fused.fusedScore()).isEqualTo(0.55);
        assertThat(fused.anomalyScore()).isEqualTo(0.55);
        assertThat(fused.trustScore()).isEqualTo(0.9);
        assertThat(fused.fusionDetail()).contains("disabled");
    }
}
