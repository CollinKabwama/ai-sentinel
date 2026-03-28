package io.aisentinel.core;

import io.aisentinel.core.enforcement.EnforcementHandler;
import io.aisentinel.core.feature.FeatureExtractor;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.policy.PolicyEngine;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.scoring.AnomalyScorer;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies pipeline clampScore: NaN or negative scorer output becomes 1.0 so policy sees high risk (no bypass).
 */
class SentinelPipelineTest {

    @Test
    void nanScoreClampedToOnePolicySeesQuarantine() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenReturn(features);

        AnomalyScorer nanScorer = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures f) { return Double.NaN; }
            @Override
            public void update(RequestFeatures f) {}
        };
        PolicyEngine policy = new io.aisentinel.core.policy.ThresholdPolicyEngine();
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.QUARANTINE), any(), any(), eq("h"), eq("/api"))).thenReturn(false);

        SentinelPipeline pipeline = new SentinelPipeline(extractor, nanScorer, policy, handler, mock(TelemetryEmitter.class), StartupGrace.NEVER, SentinelMetrics.NOOP);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        boolean proceed = pipeline.process(request, response, "h");

        assertThat(proceed).isFalse();
        verify(handler).apply(eq(EnforcementAction.QUARANTINE), eq(request), eq(response), eq("h"), eq("/api"));
    }

    @Test
    void startupGraceForcesMonitorDespiteHighRiskScore() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenReturn(features);

        AnomalyScorer nanScorer = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures f) { return Double.NaN; }
            @Override
            public void update(RequestFeatures f) {}
        };
        PolicyEngine policy = new io.aisentinel.core.policy.ThresholdPolicyEngine();
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.MONITOR), any(), any(), eq("h"), eq("/api"))).thenReturn(true);

        StartupGrace grace = () -> true;
        SentinelPipeline pipeline = new SentinelPipeline(extractor, nanScorer, policy, handler, mock(TelemetryEmitter.class), grace, SentinelMetrics.NOOP);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        boolean proceed = pipeline.process(request, response, "h");

        assertThat(proceed).isTrue();
        verify(handler).apply(eq(EnforcementAction.MONITOR), eq(request), eq(response), eq("h"), eq("/api"));
        verify(handler, never()).apply(eq(EnforcementAction.QUARANTINE), any(), any(), anyString(), anyString());
    }

    @Test
    void negativeScoreClampedToOnePolicySeesQuarantine() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenReturn(features);

        AnomalyScorer negativeScorer = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures f) { return -0.5; }
            @Override
            public void update(RequestFeatures f) {}
        };
        PolicyEngine policy = new io.aisentinel.core.policy.ThresholdPolicyEngine();
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.QUARANTINE), any(), any(), eq("h"), eq("/api"))).thenReturn(false);

        SentinelPipeline pipeline = new SentinelPipeline(extractor, negativeScorer, policy, handler, mock(TelemetryEmitter.class), StartupGrace.NEVER, SentinelMetrics.NOOP);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        boolean proceed = pipeline.process(request, response, "h");

        assertThat(proceed).isFalse();
        verify(handler).apply(eq(EnforcementAction.QUARANTINE), eq(request), eq(response), eq("h"), eq("/api"));
    }
}
