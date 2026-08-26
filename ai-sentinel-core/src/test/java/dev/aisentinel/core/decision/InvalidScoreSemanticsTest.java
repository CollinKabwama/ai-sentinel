package dev.aisentinel.core.decision;

import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.FailOpenReason;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hostile coverage for Increment 1: invalid numeric scores must not become maximum risk / quarantine.
 */
class InvalidScoreSemanticsTest {

    private static final class FixedScorer implements AnomalyScorer {
        private final double value;
        private final AtomicInteger updates = new AtomicInteger();

        FixedScorer(double value) {
            this.value = value;
        }

        @Override
        public double score(RequestFeatures features) {
            return value;
        }

        @Override
        public void update(RequestFeatures features) {
            updates.incrementAndGet();
        }
    }

    private static final class ThrowingScorer implements AnomalyScorer {
        @Override
        public double score(RequestFeatures features) {
            throw new IllegalStateException("boom");
        }

        @Override
        public void update(RequestFeatures features) {
        }
    }

    private static final class CountingPolicy implements PolicyEngine {
        private final PolicyEngine delegate = new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8);
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public EnforcementAction evaluate(double riskScore, RequestFeatures features, String endpoint) {
            invocations.incrementAndGet();
            return delegate.evaluate(riskScore, features, endpoint);
        }
    }

    private static final class RecordingTelemetry implements TelemetryEmitter {
        private final List<TelemetryEvent> events = new ArrayList<>();

        @Override
        public void emit(TelemetryEvent event) {
            events.add(event);
        }
    }

    private static final class RecordingMetrics implements SentinelMetrics {
        private final AtomicInteger invalidRejected = new AtomicInteger();
        private final AtomicInteger nanClamped = new AtomicInteger();

        @Override
        public void recordInvalidScoreRejected() {
            invalidRejected.incrementAndGet();
        }

        @Override
        public void recordNanOrNegativeScoreClamped() {
            nanClamped.incrementAndGet();
        }
    }

    private static RequestFeatures features() {
        return RequestFeatures.builder()
            .identityHash("h")
            .endpoint("/api")
            .timestampMillis(1L)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer,
                                                 PolicyEngine policy,
                                                 EnforcementHandler quarantine,
                                                 TelemetryEmitter telemetry,
                                                 SentinelMetrics metrics) {
        return new SentinelDecisionEngine(
            scorer,
            policy,
            quarantine,
            telemetry,
            StartupGrace.NEVER,
            metrics,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE);
    }

    private static EnforcementHandler neverQuarantined() {
        return new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                throw new AssertionError("engine must not apply enforcement");
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return false;
            }
        };
    }

    private void assertInvalidDecision(RiskDecision decision,
                                       CountingPolicy policy,
                                       FixedScorer scorer,
                                       RecordingTelemetry telemetry,
                                       RecordingMetrics metrics) {
        assertThat(decision).isNotNull();
        assertThat(decision.hasStatus(EvaluationStatus.INVALID_SCORE)).isTrue();
        assertThat(decision.hasStatus(EvaluationStatus.COMPLETE)).isFalse();
        assertThat(decision.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(Double.isNaN(decision.anomalyScore())).isTrue();
        assertThat(Double.isNaN(decision.policyScore())).isTrue();
        assertThat(policy.invocations.get()).isZero();
        assertThat(scorer.updates.get()).isZero();
        assertThat(metrics.invalidRejected.get()).isEqualTo(1);
        assertThat(metrics.nanClamped.get()).isZero();
        assertThat(telemetry.events).extracting(TelemetryEvent::type)
            .doesNotContain("ThreatScored", "AnomalyDetected");
        assertThat(decision.action()).isNotIn(
            EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);
    }

    @ParameterizedTest
    @ValueSource(doubles = {
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        -0.1,
        -1.0
    })
    void invalidScoresAllowWithoutPolicyOrBaselineUpdate(double raw) {
        FixedScorer scorer = new FixedScorer(raw);
        CountingPolicy policy = new CountingPolicy();
        RecordingTelemetry telemetry = new RecordingTelemetry();
        RecordingMetrics metrics = new RecordingMetrics();

        RiskDecision decision = engine(scorer, policy, neverQuarantined(), telemetry, metrics)
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertInvalidDecision(decision, policy, scorer, telemetry, metrics);
    }

    @Test
    void finiteAboveOneIsRangeClampedAndMayQuarantine() {
        FixedScorer scorer = new FixedScorer(1.5);
        CountingPolicy policy = new CountingPolicy();
        RecordingTelemetry telemetry = new RecordingTelemetry();
        RecordingMetrics metrics = new RecordingMetrics();

        RiskDecision decision = engine(scorer, policy, neverQuarantined(), telemetry, metrics)
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.hasStatus(EvaluationStatus.INVALID_SCORE)).isFalse();
        assertThat(decision.anomalyScore()).isEqualTo(1.0);
        assertThat(decision.action()).isEqualTo(EnforcementAction.QUARANTINE);
        assertThat(policy.invocations.get()).isEqualTo(1);
        assertThat(metrics.invalidRejected.get()).isZero();
    }

    @Test
    void veryLargeFiniteIsRangeClampedAndMayQuarantine() {
        RiskDecision decision = engine(new FixedScorer(1.0e9), new CountingPolicy(), neverQuarantined(),
            new RecordingTelemetry(), new RecordingMetrics())
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.hasStatus(EvaluationStatus.INVALID_SCORE)).isFalse();
        assertThat(decision.anomalyScore()).isEqualTo(1.0);
        assertThat(decision.action()).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void validZeroAllows() {
        assertThat(actionFor(0.0)).isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void validOneQuarantines() {
        assertThat(actionFor(1.0)).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void justBelowModerateThresholdAllows() {
        assertThat(actionFor(0.199)).isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void exactlyAtModerateThresholdMonitors() {
        assertThat(actionFor(0.2)).isEqualTo(EnforcementAction.MONITOR);
    }

    @Test
    void justBelowCriticalThresholdBlocks() {
        assertThat(actionFor(0.799)).isEqualTo(EnforcementAction.BLOCK);
    }

    @Test
    void exactlyAtCriticalThresholdQuarantines() {
        assertThat(actionFor(0.8)).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void scorerExceptionStillReturnsNullFailOpen() {
        RiskDecision decision = engine(new ThrowingScorer(), new CountingPolicy(), neverQuarantined(),
            new RecordingTelemetry(), SentinelMetrics.NOOP)
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());
        assertThat(decision).isNull();
    }

    @Test
    void featureExtractionExceptionStillFailOpensWithoutDecision() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        when(extractor.extract(any(), eq("h"), any(RequestContext.class)))
            .thenThrow(new IllegalStateException("features unavailable"));

        AtomicInteger failOpen = new AtomicInteger();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordFailOpen(FailOpenReason reason) {
                if (reason == FailOpenReason.FEATURE_EXTRACTION_FAILURE) {
                    failOpen.incrementAndGet();
                }
            }
        };

        SentinelPipeline pipeline = new SentinelPipeline(
            extractor,
            new FixedScorer(0.95),
            new ThresholdPolicyEngine(),
            neverQuarantined(),
            mock(TelemetryEmitter.class),
            StartupGrace.NEVER,
            metrics);

        boolean proceed = pipeline.process(mock(HttpRequestView.class), mock(EnforcementResponse.class), "h");
        assertThat(proceed).isTrue();
        assertThat(failOpen.get()).isEqualTo(1);
    }

    @Test
    void invalidScoreDoesNotCreateQuarantineEntry() {
        CompositeEnforcementHandler handler = new CompositeEnforcementHandler(
            403, 60_000L, 10.0, mock(TelemetryEmitter.class));
        CountingPolicy policy = new CountingPolicy();
        FixedScorer scorer = new FixedScorer(Double.NaN);

        SentinelDecisionEngine decisionEngine = engine(scorer, policy, handler, new RecordingTelemetry(), new RecordingMetrics());
        RiskDecision decision = decisionEngine.evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(handler.isQuarantined("h", "/api")).isFalse();
        assertThat(handler.getQuarantineCount()).isZero();

        // Applying ALLOW must not quarantine.
        handler.apply(decision.action(), new MapHttpRequestView(), mock(EnforcementResponse.class), "h", "/api");
        assertThat(handler.isQuarantined("h", "/api")).isFalse();
        assertThat(handler.getQuarantineCount()).isZero();
    }

    @Test
    void alreadyQuarantinedIdentityStillSeesQuarantineOverrideOnInvalidScore() {
        EnforcementHandler already = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                throw new AssertionError("engine must not apply");
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return true;
            }
        };
        RiskDecision decision = engine(new FixedScorer(Double.NaN), new CountingPolicy(), already,
            new RecordingTelemetry(), new RecordingMetrics())
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.hasStatus(EvaluationStatus.INVALID_SCORE)).isTrue();
        assertThat(decision.action()).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void pipelineInvalidScoreAppliesAllowNotQuarantine() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        when(extractor.extract(any(), eq("h"), any(RequestContext.class))).thenReturn(features());

        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.ALLOW), any(), any(), eq("h"), eq("/api"))).thenReturn(true);

        SentinelPipeline pipeline = new SentinelPipeline(
            extractor,
            new FixedScorer(Double.NaN),
            new ThresholdPolicyEngine(),
            handler,
            mock(TelemetryEmitter.class),
            StartupGrace.NEVER,
            SentinelMetrics.NOOP);

        boolean proceed = pipeline.process(mock(HttpRequestView.class), mock(EnforcementResponse.class), "h");
        assertThat(proceed).isTrue();
        verify(handler).apply(eq(EnforcementAction.ALLOW), any(), any(), eq("h"), eq("/api"));
        verify(handler, never()).apply(eq(EnforcementAction.QUARANTINE), any(), any(), anyString(), anyString());
    }

    private static EnforcementAction actionFor(double score) {
        return engine(new FixedScorer(score), new CountingPolicy(), neverQuarantined(),
            new RecordingTelemetry(), new RecordingMetrics())
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext())
            .action();
    }
}
