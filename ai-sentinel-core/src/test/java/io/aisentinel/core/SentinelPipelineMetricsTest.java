package io.aisentinel.core;

import io.aisentinel.core.enforcement.EnforcementHandler;
import io.aisentinel.core.feature.FeatureExtractor;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.policy.PolicyEngine;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.scoring.AnomalyScorer;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SentinelPipelineMetricsTest {

    @Test
    void scoringFailureIncrementsSafetyCounters() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenReturn(features);

        AnomalyScorer badScorer = mock(AnomalyScorer.class);
        when(badScorer.score(any())).thenThrow(new RuntimeException("boom"));

        PolicyEngine policy = mock(PolicyEngine.class);
        EnforcementHandler handler = mock(EnforcementHandler.class);
        SentinelMetrics metrics = mock(SentinelMetrics.class);

        SentinelPipeline pipeline = new SentinelPipeline(
            extractor, badScorer, policy, handler, mock(TelemetryEmitter.class), StartupGrace.NEVER, metrics);

        boolean proceed = pipeline.process(mock(HttpServletRequest.class), mock(HttpServletResponse.class), "h");

        assertThat(proceed).isTrue();
        verify(metrics).recordScoringError();
        verify(metrics).recordFailOpen();
        verify(metrics).recordScoringLatencyNanos(anyLong());
        verify(metrics).recordPipelineLatencyNanos(anyLong());
        verifyNoInteractions(handler);
    }

    @Test
    void recordsPolicyActionBeforeApply() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenReturn(features);

        AnomalyScorer scorer = mock(AnomalyScorer.class);
        when(scorer.score(any())).thenReturn(0.05);

        PolicyEngine policy = new io.aisentinel.core.policy.ThresholdPolicyEngine();
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(any(), any())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.ALLOW), any(), any(), eq("h"), eq("/api"))).thenReturn(true);

        SentinelMetrics metrics = mock(SentinelMetrics.class);
        SentinelPipeline pipeline = new SentinelPipeline(
            extractor, scorer, policy, handler, mock(TelemetryEmitter.class), StartupGrace.NEVER, metrics);

        pipeline.process(mock(HttpServletRequest.class), mock(HttpServletResponse.class), "h");

        verify(metrics).recordPolicyAction(EnforcementAction.ALLOW);
        verify(metrics).recordPipelineLatencyNanos(anyLong());
    }
}
