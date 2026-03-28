package io.aisentinel.autoconfigure.metrics;

import io.aisentinel.core.policy.EnforcementAction;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerSentinelMetricsTest {

    @Test
    void registersExpectedMetersAndIncrementsCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSentinelMetrics m = new MicrometerSentinelMetrics(registry);

        m.recordCompositeScore(0.42);
        m.recordStatisticalScore(0.4);
        m.recordIsolationForestScore(0.5);
        m.recordPolicyAction(EnforcementAction.ALLOW);
        m.recordFailOpen();
        m.recordNanOrNegativeScoreClamped();
        m.recordScoringError();
        m.recordPipelineLatencyNanos(1_000_000L);
        m.recordScoringLatencyNanos(500_000L);
        m.recordIsolationForestInferenceLatencyNanos(100_000L);
        m.recordRetrainSuccessNanos(2_000_000L);
        m.recordRetrainFailureNanos(1_000_000L);

        assertThat(registry.find("aisentinel.score.composite").summary().count()).isEqualTo(1L);
        assertThat(registry.find("aisentinel.action.allow").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("aisentinel.failopen.count").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("aisentinel.nan.clamped.count").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("aisentinel.errors.scoring.count").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("aisentinel.latency.pipeline").timer().count()).isEqualTo(1L);
        assertThat(registry.find("aisentinel.model.retrain.success").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("aisentinel.model.retrain.failure").counter().count()).isEqualTo(1.0);
        assertThat(m.getRetrainSuccessCount()).isEqualTo(1L);
        assertThat(m.getRetrainFailureCount()).isEqualTo(1L);

        assertThat(m.scoreSummaryForActuator()).containsKeys("composite", "statistical", "if");
        assertThat(m.latencySummaryForActuator()).containsKeys("pipeline", "scoring", "if");
    }
}
