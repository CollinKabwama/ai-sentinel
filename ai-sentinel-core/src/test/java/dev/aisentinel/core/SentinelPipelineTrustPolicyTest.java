package dev.aisentinel.core;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.identity.IdentityContextKeys;
import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.IdentityRiskSignals;
import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.identity.model.TrustScore;
import dev.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import dev.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.policy.TrustPolicyAdjuster;
import dev.aisentinel.core.policy.TrustPolicyAdjustment;
import dev.aisentinel.core.policy.TrustPolicyContextKeys;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

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
        AtomicReference<RequestContext> ctxRef = new AtomicReference<>();
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenAnswer(inv -> {
            RequestContext ctx = inv.getArgument(2);
            ctxRef.set(ctx);
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

        HttpRequestView request = mock(HttpRequestView.class);
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
            dev.aisentinel.distributed.training.NoopTrainingCandidatePublisher.INSTANCE,
            dev.aisentinel.core.enforcement.EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "ENFORCE",
            NoopIdentityContextResolver.INSTANCE,
            NoopTrustEvaluator.INSTANCE,
            adjuster,
            NoopIdentityResponseHook.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );

        EnforcementResponse response = mock(EnforcementResponse.class);
        assertThat(pipeline.process(request, response, "h")).isTrue();
        verify(handler).apply(eq(EnforcementAction.MONITOR), eq(request), eq(response), eq("h"), eq("/api"));
        assertThat(ctxRef.get().get(TrustPolicyContextKeys.TRUST_POLICY_DETAIL, String.class))
            .isEqualTo("trust-policy:test-escalation");
    }

    @Test
    void startupGraceOverridesTrustEscalationToMonitor() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        AtomicReference<RequestContext> ctxRef = new AtomicReference<>();
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenAnswer(inv -> {
            RequestContext ctx = inv.getArgument(2);
            ctxRef.set(ctx);
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

        HttpRequestView request = mock(HttpRequestView.class);
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
            dev.aisentinel.distributed.training.NoopTrainingCandidatePublisher.INSTANCE,
            dev.aisentinel.core.enforcement.EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "ENFORCE",
            NoopIdentityContextResolver.INSTANCE,
            NoopTrustEvaluator.INSTANCE,
            adjuster,
            NoopIdentityResponseHook.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );

        EnforcementResponse response = mock(EnforcementResponse.class);
        assertThat(pipeline.process(request, response, "h")).isTrue();
        verify(handler).apply(eq(EnforcementAction.MONITOR), eq(request), eq(response), eq("h"), eq("/api"));
        assertThat(ctxRef.get().get(TrustPolicyContextKeys.TRUST_POLICY_DETAIL, String.class))
            .isEqualTo("trust-policy:escalate");
    }
}
