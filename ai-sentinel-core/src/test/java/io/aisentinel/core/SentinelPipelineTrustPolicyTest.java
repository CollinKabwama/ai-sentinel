package io.aisentinel.core;

import io.aisentinel.core.enforcement.EnforcementHandler;
import io.aisentinel.core.feature.FeatureExtractor;
import io.aisentinel.core.identity.IdentityContextKeys;
import io.aisentinel.core.identity.model.AuthenticationContext;
import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.IdentityRiskSignals;
import io.aisentinel.core.identity.model.SessionContext;
import io.aisentinel.core.identity.model.TrustScore;
import io.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import io.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import io.aisentinel.core.identity.spi.NoopTrustEvaluator;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.fusion.NoopRequestRiskFusion;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.policy.PolicyEngine;
import io.aisentinel.core.policy.TrustPolicyAdjuster;
import io.aisentinel.core.policy.TrustPolicyAdjustment;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.scoring.AnomalyScorer;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SentinelPipelineTrustPolicyTest {

    @Test
    void trustPolicyDetailStoredOnContextWhenAdjusterEscalates() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenAnswer(inv -> {
            RequestContext ctx = inv.getArgument(2);
            IdentityContext ic = new IdentityContext(
                AuthenticationContext.ofPrincipal("u"),
                SessionContext.none(),
                new TrustScore(0.5, "t"),
                IdentityRiskSignals.empty());
            ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, ic);
            return features;
        });

        AnomalyScorer scorer = mock(AnomalyScorer.class);
        when(scorer.score(any())).thenReturn(0.05);

        PolicyEngine policy = mock(PolicyEngine.class);
        when(policy.evaluate(anyDouble(), any(), eq("/api"))).thenReturn(EnforcementAction.ALLOW);

        TrustPolicyAdjuster adjuster = (baseline, riskScore, f, endpoint, request, ctx) ->
            new TrustPolicyAdjustment(EnforcementAction.MONITOR, "trust-policy:test-escalation");

        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.MONITOR), any(), any(), eq("h"), eq("/api"))).thenReturn(true);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");

        SentinelPipeline pipeline = new SentinelPipeline(
            extractor,
            scorer,
            null,
            policy,
            handler,
            mock(TelemetryEmitter.class),
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            io.aisentinel.distributed.training.NoopTrainingCandidatePublisher.INSTANCE,
            io.aisentinel.core.enforcement.EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "ENFORCE",
            NoopIdentityContextResolver.INSTANCE,
            NoopTrustEvaluator.INSTANCE,
            adjuster,
            NoopIdentityResponseHook.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );

        HttpServletResponse response = mock(HttpServletResponse.class);
        assertThat(pipeline.process(request, response, "h")).isTrue();
        verify(handler).apply(eq(EnforcementAction.MONITOR), eq(request), eq(response), eq("h"), eq("/api"));
    }

    @Test
    void startupGraceOverridesTrustEscalationToMonitor() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenAnswer(inv -> {
            RequestContext ctx = inv.getArgument(2);
            IdentityContext ic = new IdentityContext(
                AuthenticationContext.ofPrincipal("u"),
                SessionContext.none(),
                new TrustScore(0.1, "t"),
                IdentityRiskSignals.empty());
            ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, ic);
            return features;
        });

        AnomalyScorer scorer = mock(AnomalyScorer.class);
        when(scorer.score(any())).thenReturn(0.05);

        PolicyEngine policy = mock(PolicyEngine.class);
        when(policy.evaluate(anyDouble(), any(), eq("/api"))).thenReturn(EnforcementAction.ALLOW);

        TrustPolicyAdjuster adjuster = (baseline, riskScore, f, endpoint, request, ctx) ->
            new TrustPolicyAdjustment(EnforcementAction.THROTTLE, "trust-policy:escalate");

        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.MONITOR), any(), any(), eq("h"), eq("/api"))).thenReturn(true);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");

        StartupGrace graceActive = () -> true;

        SentinelPipeline pipeline = new SentinelPipeline(
            extractor,
            scorer,
            null,
            policy,
            handler,
            mock(TelemetryEmitter.class),
            graceActive,
            SentinelMetrics.NOOP,
            io.aisentinel.distributed.training.NoopTrainingCandidatePublisher.INSTANCE,
            io.aisentinel.core.enforcement.EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "ENFORCE",
            NoopIdentityContextResolver.INSTANCE,
            NoopTrustEvaluator.INSTANCE,
            adjuster,
            NoopIdentityResponseHook.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );

        HttpServletResponse response = mock(HttpServletResponse.class);
        assertThat(pipeline.process(request, response, "h")).isTrue();
        verify(handler).apply(eq(EnforcementAction.MONITOR), eq(request), eq(response), eq("h"), eq("/api"));
    }
}
