package dev.aisentinel.core;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.fusion.DeterministicRequestRiskFusion;
import dev.aisentinel.core.fusion.FusedRisk;
import dev.aisentinel.core.fusion.FusionContextKeys;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.fusion.RequestRiskFusion;
import dev.aisentinel.core.identity.IdentityContextKeys;
import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.IdentityRiskSignals;
import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.identity.model.TrustScore;
import dev.aisentinel.core.identity.spi.IdentityContextResolver;
import dev.aisentinel.core.identity.spi.IdentityResponseHook;
import dev.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import dev.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.distributed.training.NoopTrainingCandidatePublisher;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SentinelPipelineFusionTest {

    private static RequestFeatures features() {
        return RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
    }

    private static SentinelPipeline pipeline(IdentityContextResolver resolver,
                                             RequestRiskFusion fusion,
                                             PolicyEngine policy,
                                             AnomalyScorer scorer,
                                             IdentityResponseHook hook) {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenReturn(features());
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(any(), any(), any(), eq("h"), eq("/api"))).thenReturn(true);
        return new SentinelPipeline(
            extractor,
            scorer,
            null,
            policy,
            handler,
            mock(TelemetryEmitter.class),
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrainingCandidatePublisher.INSTANCE,
            EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "ENFORCE",
            resolver,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            hook,
            fusion
        );
    }

    @Test
    void fusionDisabledPassesAnomalyScoreToPolicyUnchanged() throws Exception {
        IdentityContextResolver resolver = (req, hash, ctx) -> {
            IdentityContext ic = new IdentityContext(
                AuthenticationContext.ofPrincipal("u"),
                SessionContext.none(),
                new TrustScore(0.05, "low"),
                IdentityRiskSignals.empty());
            ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, ic);
        };
        PolicyEngine policy = mock(PolicyEngine.class);
        when(policy.evaluate(anyDouble(), any(), eq("/api"))).thenReturn(EnforcementAction.ALLOW);
        AnomalyScorer scorer = mock(AnomalyScorer.class);
        when(scorer.score(any())).thenReturn(0.35);
        SentinelPipeline p = pipeline(resolver, NoopRequestRiskFusion.INSTANCE, policy, scorer, NoopIdentityResponseHook.INSTANCE);
        p.process(mock(HttpRequestView.class), mock(EnforcementResponse.class), "h");
        ArgumentCaptor<Double> scoreCap = ArgumentCaptor.forClass(Double.class);
        verify(policy).evaluate(scoreCap.capture(), any(), eq("/api"));
        assertThat(scoreCap.getValue()).isEqualTo(0.35);
    }

    @Test
    void fusionEnabledWithoutIdentityContextBehavesLikeFusionOff() throws Exception {
        PolicyEngine policy = mock(PolicyEngine.class);
        when(policy.evaluate(anyDouble(), any(), eq("/api"))).thenReturn(EnforcementAction.ALLOW);
        AnomalyScorer scorer = mock(AnomalyScorer.class);
        when(scorer.score(any())).thenReturn(0.35);
        SentinelPipeline p = pipeline(
            NoopIdentityContextResolver.INSTANCE,
            new DeterministicRequestRiskFusion(0.9),
            policy,
            scorer,
            NoopIdentityResponseHook.INSTANCE);
        p.process(mock(HttpRequestView.class), mock(EnforcementResponse.class), "h");
        ArgumentCaptor<Double> scoreCap = ArgumentCaptor.forClass(Double.class);
        verify(policy).evaluate(scoreCap.capture(), any(), eq("/api"));
        assertThat(scoreCap.getValue()).isEqualTo(0.35);
    }

    @Test
    void lowTrustElevatesPolicyScoreVersusFusionDisabled() throws Exception {
        IdentityContextResolver resolver = (req, hash, ctx) -> {
            IdentityContext ic = new IdentityContext(
                AuthenticationContext.ofPrincipal("u"),
                SessionContext.none(),
                new TrustScore(0.05, "low"),
                IdentityRiskSignals.empty());
            ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, ic);
        };
        PolicyEngine policyNoFusion = mock(PolicyEngine.class);
        when(policyNoFusion.evaluate(anyDouble(), any(), eq("/api"))).thenReturn(EnforcementAction.MONITOR);
        AnomalyScorer scorer = mock(AnomalyScorer.class);
        when(scorer.score(any())).thenReturn(0.35);
        SentinelPipeline noFusion = pipeline(resolver, NoopRequestRiskFusion.INSTANCE, policyNoFusion, scorer, NoopIdentityResponseHook.INSTANCE);
        noFusion.process(mock(HttpRequestView.class), mock(EnforcementResponse.class), "h");
        ArgumentCaptor<Double> capNo = ArgumentCaptor.forClass(Double.class);
        verify(policyNoFusion).evaluate(capNo.capture(), any(), eq("/api"));
        assertThat(capNo.getValue()).isEqualTo(0.35);

        PolicyEngine policyFused = new ThresholdPolicyEngine();
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(any(), any(), any(), eq("h"), eq("/api"))).thenReturn(true);
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenReturn(features());
        SentinelPipeline fusedPipeline = new SentinelPipeline(
            extractor,
            scorer,
            null,
            policyFused,
            handler,
            mock(TelemetryEmitter.class),
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrainingCandidatePublisher.INSTANCE,
            EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "ENFORCE",
            resolver,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopIdentityResponseHook.INSTANCE,
            new DeterministicRequestRiskFusion(0.9));
        assertThat(fusedPipeline.process(mock(HttpRequestView.class), mock(EnforcementResponse.class), "h")).isTrue();
        verify(handler).apply(argThat(a -> a != EnforcementAction.ALLOW && a != EnforcementAction.MONITOR),
            any(), any(), eq("h"), eq("/api"));
    }

    @Test
    void fusionFailureIsFailOpenAndUsesAnomalyScore() throws Exception {
        IdentityContextResolver resolver = (req, hash, ctx) -> {
            IdentityContext ic = new IdentityContext(
                AuthenticationContext.ofPrincipal("u"),
                SessionContext.none(),
                new TrustScore(0.1, "t"),
                IdentityRiskSignals.empty());
            ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, ic);
        };
        RequestRiskFusion throwing = new RequestRiskFusion() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public FusedRisk fuse(double anomalyScoreClamped, double trustScoreClamped) {
                throw new RuntimeException("fusion boom");
            }
        };
        AtomicInteger failOpen = new AtomicInteger();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordFailOpen() {
                failOpen.incrementAndGet();
            }
        };
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenReturn(features());
        AnomalyScorer scorer = mock(AnomalyScorer.class);
        when(scorer.score(any())).thenReturn(0.22);
        PolicyEngine policy = mock(PolicyEngine.class);
        when(policy.evaluate(anyDouble(), any(), eq("/api"))).thenReturn(EnforcementAction.ALLOW);
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.ALLOW), any(), any(), eq("h"), eq("/api"))).thenReturn(true);
        SentinelPipeline p = new SentinelPipeline(
            extractor,
            scorer,
            null,
            policy,
            handler,
            mock(TelemetryEmitter.class),
            StartupGrace.NEVER,
            metrics,
            NoopTrainingCandidatePublisher.INSTANCE,
            EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "ENFORCE",
            resolver,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopIdentityResponseHook.INSTANCE,
            throwing);
        assertThat(p.process(mock(HttpRequestView.class), mock(EnforcementResponse.class), "h")).isTrue();
        assertThat(failOpen.get()).isEqualTo(1);
        ArgumentCaptor<Double> cap = ArgumentCaptor.forClass(Double.class);
        verify(policy).evaluate(cap.capture(), any(), eq("/api"));
        assertThat(cap.getValue()).isEqualTo(0.22);
    }

    @Test
    void fusionStoresFusedRiskOnContextForHooks() throws Exception {
        AtomicReference<RequestContext> ctxSeen = new AtomicReference<>();
        IdentityResponseHook hook = (req, res, hash, feats, ctx, ok) -> ctxSeen.set(ctx);
        IdentityContextResolver resolver = (req, hash, ctx) -> {
            IdentityContext ic = new IdentityContext(
                AuthenticationContext.ofPrincipal("u"),
                SessionContext.none(),
                new TrustScore(0.5, "m"),
                IdentityRiskSignals.empty());
            ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, ic);
        };
        PolicyEngine policy = mock(PolicyEngine.class);
        when(policy.evaluate(anyDouble(), any(), eq("/api"))).thenReturn(EnforcementAction.ALLOW);
        AnomalyScorer scorer = mock(AnomalyScorer.class);
        when(scorer.score(any())).thenReturn(0.3);
        SentinelPipeline p = pipeline(resolver, new DeterministicRequestRiskFusion(0.4), policy, scorer, hook);
        p.process(mock(HttpRequestView.class), mock(EnforcementResponse.class), "h");
        FusedRisk fr = ctxSeen.get().get(FusionContextKeys.FUSED_RISK, FusedRisk.class);
        assertThat(fr).isNotNull();
        assertThat(fr.anomalyScore()).isEqualTo(0.3);
        assertThat(fr.trustScore()).isEqualTo(0.5);
        assertThat(fr.fusedScore()).isBetween(0.0, 1.0);
        assertThat(fr.fusionDetail()).startsWith("fusion:anomaly=");
    }
}
