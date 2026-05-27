package io.aisentinel.core.fusion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicRequestRiskFusionTest {

    @Test
    void highTrustAndMediumAnomalyReducesFusedBelowAnomaly() {
        DeterministicRequestRiskFusion f = new DeterministicRequestRiskFusion(0.5);
        FusedRisk r = f.fuse(0.4, 0.98);
        assertThat(r.fusedScore()).isLessThan(0.4);
        assertThat(r.anomalyScore()).isEqualTo(0.4);
        assertThat(r.trustScore()).isEqualTo(0.98);
    }

    @Test
    void lowTrustAndMediumAnomalyIncreasesFused() {
        DeterministicRequestRiskFusion f = new DeterministicRequestRiskFusion(0.9);
        FusedRisk r = f.fuse(0.35, 0.05);
        assertThat(r.fusedScore()).isGreaterThan(0.35);
    }

    @Test
    void veryLowTrustAndLowAnomalyStillElevatesVersusAnomalyAlone() {
        DeterministicRequestRiskFusion f = new DeterministicRequestRiskFusion(0.5);
        FusedRisk r = f.fuse(0.1, 0.05);
        assertThat(r.fusedScore()).isGreaterThan(0.1);
    }

    @Test
    void highAnomalyAndHighTrustStaysHigh() {
        DeterministicRequestRiskFusion f = new DeterministicRequestRiskFusion(0.9);
        FusedRisk r = f.fuse(0.95, 0.95);
        assertThat(r.fusedScore()).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    void explainabilityDetailIncludesComponents() {
        DeterministicRequestRiskFusion f = new DeterministicRequestRiskFusion(0.35);
        FusedRisk r = f.fuse(0.25, 0.5);
        assertThat(r.fusionDetail())
            .contains("fusion:anomaly=")
            .contains("trust=")
            .contains("fused=");
    }

    @Test
    void invalidStrengthBecomesClamped() {
        DeterministicRequestRiskFusion f = new DeterministicRequestRiskFusion(Double.NaN);
        FusedRisk r = f.fuse(0.3, 0.3);
        assertThat(r.fusionDetail()).contains("strength=0.0000");
    }

    @Test
    void zeroAnomalyAndZeroTrustUsesAdditiveDistrustFloor() {
        DeterministicRequestRiskFusion f = new DeterministicRequestRiskFusion(0.35);
        FusedRisk r = f.fuse(0.0, 0.0);
        double k = 0.35;
        double expectedAdditive = 0.15 * k * 1.0;
        assertThat(r.fusedScore()).isEqualTo(expectedAdditive);
        assertThat(r.anomalyScore()).isZero();
        assertThat(r.trustScore()).isZero();
    }

    @Test
    void zeroStrengthPassesThroughAnomalyExactly() {
        DeterministicRequestRiskFusion f = new DeterministicRequestRiskFusion(0.0);
        FusedRisk r = f.fuse(0.42, 0.11);
        assertThat(r.fusedScore()).isEqualTo(0.42);
        assertThat(r.anomalyScore()).isEqualTo(0.42);
        assertThat(r.trustScore()).isEqualTo(0.11);
    }

    @Test
    void maxAnomalyAndZeroTrustCapsAtOne() {
        DeterministicRequestRiskFusion f = new DeterministicRequestRiskFusion(1.0);
        FusedRisk r = f.fuse(1.0, 0.0);
        assertThat(r.fusedScore()).isEqualTo(1.0);
        assertThat(r.anomalyScore()).isEqualTo(1.0);
        assertThat(r.trustScore()).isZero();
    }
}
