package dev.aisentinel.core;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pipeline invalid-score semantics: NaN / negative scorer output → ALLOW + INVALID_SCORE (not max-risk quarantine).
 */
class SentinelPipelineTest {

    @Test
    void nanScoreIsInvalidAllowNotQuarantine() throws Exception {
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
        PolicyEngine policy = new dev.aisentinel.core.policy.ThresholdPolicyEngine();
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.ALLOW), any(), any(), eq("h"), eq("/api"))).thenReturn(true);

        SentinelPipeline pipeline = new SentinelPipeline(extractor, nanScorer, policy, handler, mock(TelemetryEmitter.class), StartupGrace.NEVER, SentinelMetrics.NOOP);
        HttpRequestView request = mock(HttpRequestView.class);
        EnforcementResponse response = mock(EnforcementResponse.class);

        boolean proceed = pipeline.process(request, response, "h");

        assertThat(proceed).isTrue();
        verify(handler).apply(eq(EnforcementAction.ALLOW), eq(request), eq(response), eq("h"), eq("/api"));
        verify(handler, never()).apply(eq(EnforcementAction.QUARANTINE), any(), any(), anyString(), anyString());
    }

    @Test
    void startupGraceForcesMonitorDespiteHighRiskScore() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenReturn(features);

        AnomalyScorer highRiskScorer = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures f) { return 0.95; }
            @Override
            public void update(RequestFeatures f) {}
        };
        PolicyEngine policy = new dev.aisentinel.core.policy.ThresholdPolicyEngine();
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.MONITOR), any(), any(), eq("h"), eq("/api"))).thenReturn(true);

        StartupGrace grace = () -> true;
        SentinelPipeline pipeline = new SentinelPipeline(extractor, highRiskScorer, policy, handler, mock(TelemetryEmitter.class), grace, SentinelMetrics.NOOP);
        HttpRequestView request = mock(HttpRequestView.class);
        EnforcementResponse response = mock(EnforcementResponse.class);

        boolean proceed = pipeline.process(request, response, "h");

        assertThat(proceed).isTrue();
        verify(handler).apply(eq(EnforcementAction.MONITOR), eq(request), eq(response), eq("h"), eq("/api"));
        verify(handler, never()).apply(eq(EnforcementAction.QUARANTINE), any(), any(), anyString(), anyString());
    }

    @Test
    void negativeScoreIsInvalidAllowNotQuarantine() throws Exception {
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
        PolicyEngine policy = new dev.aisentinel.core.policy.ThresholdPolicyEngine();
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.ALLOW), any(), any(), eq("h"), eq("/api"))).thenReturn(true);

        SentinelPipeline pipeline = new SentinelPipeline(extractor, negativeScorer, policy, handler, mock(TelemetryEmitter.class), StartupGrace.NEVER, SentinelMetrics.NOOP);
        HttpRequestView request = mock(HttpRequestView.class);
        EnforcementResponse response = mock(EnforcementResponse.class);

        boolean proceed = pipeline.process(request, response, "h");

        assertThat(proceed).isTrue();
        verify(handler).apply(eq(EnforcementAction.ALLOW), eq(request), eq(response), eq("h"), eq("/api"));
        verify(handler, never()).apply(eq(EnforcementAction.QUARANTINE), any(), any(), anyString(), anyString());
    }
}
