package dev.aisentinel.core.decision;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.IdentityContextKeys;
import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.IdentityRiskSignals;
import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.identity.model.TrustScore;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives {@link SentinelDecisionEngine} with no servlet container, no Spring context, and no mocking framework:
 * features are built directly, the scorer is a constant, and the policy engine uses known thresholds.
 */
class SentinelDecisionEngineDirectTest {

    /** Deterministic scorer so the decision under test depends only on policy thresholds. */
    private static final class FixedAnomalyScorer implements AnomalyScorer {
        private final double value;
        private int updates;

        private FixedAnomalyScorer(double value) {
            this.value = value;
        }

        @Override
        public double score(RequestFeatures features) {
            return value;
        }

        @Override
        public void update(RequestFeatures features) {
            updates++;
        }
    }

    private static final class RecordingTelemetry implements TelemetryEmitter {
        private final List<TelemetryEvent> events = new ArrayList<>();

        @Override
        public void emit(TelemetryEvent event) {
            events.add(event);
        }
    }

    private static final EnforcementHandler NEVER_QUARANTINED = new EnforcementHandler() {
        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("decision engine must not apply enforcement");
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    };

    private static RequestFeatures features() {
        return RequestFeatures.builder()
            .identityHash("h")
            .endpoint("/api/orders")
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(3)
            .endpointEntropy(0.4)
            .tokenAgeSeconds(120)
            .parameterCount(2)
            .payloadSizeBytes(512)
            .headerFingerprintHash(99L)
            .ipBucket(7)
            .build();
    }

    private static RequestContext contextWithTrust(double trust) {
        RequestContext ctx = new RequestContext();
        ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, new IdentityContext(
            AuthenticationContext.ofPrincipal("alice"),
            SessionContext.none(),
            new TrustScore(trust, "fixed"),
            IdentityRiskSignals.empty()));
        return ctx;
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer, StartupGrace grace, TelemetryEmitter telemetry) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NEVER_QUARANTINED,
            telemetry,
            grace,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE);
    }

    @Test
    void scoreBelowModerateThresholdAllows() {
        RiskDecision decision = engine(new FixedAnomalyScorer(0.10), StartupGrace.NEVER, new RecordingTelemetry())
            .evaluate(new MapHttpRequestView(), "h", features(), contextWithTrust(1.0));

        assertThat(decision).isNotNull();
        assertThat(decision.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(decision.anomalyScore()).isEqualTo(0.10);
        assertThat(decision.policyScore()).isEqualTo(0.10);
        assertThat(decision.startupGraceActive()).isFalse();
    }

    @Test
    void scoreInEachBandMapsToExpectedAction() {
        assertThat(actionFor(0.10)).isEqualTo(EnforcementAction.ALLOW);
        assertThat(actionFor(0.25)).isEqualTo(EnforcementAction.MONITOR);
        assertThat(actionFor(0.45)).isEqualTo(EnforcementAction.THROTTLE);
        assertThat(actionFor(0.65)).isEqualTo(EnforcementAction.BLOCK);
        assertThat(actionFor(0.95)).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void nanScoreIsClampedToHighRiskQuarantine() {
        RiskDecision decision = engine(new FixedAnomalyScorer(Double.NaN), StartupGrace.NEVER, new RecordingTelemetry())
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.anomalyScore()).isEqualTo(1.0);
        assertThat(decision.action()).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void startupGraceForcesMonitorAndIsReportedOnTheDecision() {
        RiskDecision decision = engine(new FixedAnomalyScorer(0.95), () -> true, new RecordingTelemetry())
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(decision.startupGraceActive()).isTrue();
    }

    @Test
    void quarantinedIdentityOverridesPolicyAction() {
        EnforcementHandler quarantined = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                throw new AssertionError("decision engine must not apply enforcement");
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return true;
            }
        };
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            new FixedAnomalyScorer(0.01),
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            quarantined,
            new RecordingTelemetry(),
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE);

        RiskDecision decision = engine.evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision.action()).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void scorerFailureReturnsNullSoCallersFailOpen() {
        AnomalyScorer boom = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                throw new IllegalStateException("model unavailable");
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };

        RiskDecision decision = engine(boom, StartupGrace.NEVER, new RecordingTelemetry())
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(decision).isNull();
    }

    @Test
    void scorerIsUpdatedAndTelemetryEmittedWithoutAnyHttpTypes() {
        FixedAnomalyScorer scorer = new FixedAnomalyScorer(0.1);
        RecordingTelemetry telemetry = new RecordingTelemetry();

        engine(scorer, StartupGrace.NEVER, telemetry)
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());

        assertThat(scorer.updates).isEqualTo(1);
        assertThat(telemetry.events).extracting(TelemetryEvent::type)
            .containsExactly("ThreatScored");
    }

    @Test
    void elevatedRiskDoesNotUpdateScorerByDefault() {
        FixedAnomalyScorer scorer = new FixedAnomalyScorer(0.75);
        RiskDecision decision = engine(scorer, StartupGrace.NEVER, new RecordingTelemetry())
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());
        assertThat(decision.hasStatus(EvaluationStatus.BASELINE_UPDATE_SKIPPED)).isTrue();
        assertThat(scorer.updates).isEqualTo(0);
    }

    private static EnforcementAction actionFor(double score) {
        RiskDecision decision = engine(new FixedAnomalyScorer(score), StartupGrace.NEVER, new RecordingTelemetry())
            .evaluate(new MapHttpRequestView(), "h", features(), new RequestContext());
        return decision.action();
    }
}
