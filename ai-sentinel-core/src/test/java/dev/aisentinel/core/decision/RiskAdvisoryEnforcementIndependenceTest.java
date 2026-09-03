package dev.aisentinel.core.decision;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.StatisticalScoreSnapshot;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hostile + positive integration: advisory metadata must not change enforcement.
 */
class RiskAdvisoryEnforcementIndependenceTest {

    @Test
    void advisoryAndFactorsCannotChangeEnforcementAction() {
        CapturingPolicyEngine policy = new CapturingPolicyEngine(EnforcementAction.THROTTLE);
        List<TelemetryEvent> events = new ArrayList<>();
        SentinelDecisionEngine engine = engine(new FixedScorer(0.55), policy, events::add, false);

        RiskDecision d1 = engine.evaluate(mockRequest(), "idhash", features(), new RequestContext());
        RiskDecision d2 = d1.withExplanation(RiskExplanationDeriver.derive(
            EnforcementAction.QUARANTINE,
            Set.of(EvaluationStatus.COMPLETE),
            DecisionExplanationEvidence.fromStatistical(0.99,
                new StatisticalScoreSnapshot(0.99, false, "requestsPerWindow", 50.0, 1.0, 1.0, 6.0, 6.0)),
            null, 0.99, 0.99));

        assertEquals(EnforcementAction.THROTTLE, d1.action());
        assertEquals(EnforcementAction.THROTTLE, d2.action());
        assertEquals(1, policy.calls);
        assertNotNull(d1.explanation());
    }

    @Test
    void factorSeverityCannotDirectlyTriggerQuarantine() {
        PolicyEngine policy = new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8);
        SentinelDecisionEngine engine = engine(new FixedScorer(0.35), policy, e -> {}, false);
        RiskDecision decision = engine.evaluate(mockRequest(), "idhash", features(), new RequestContext());
        assertEquals(EnforcementAction.MONITOR, decision.action());
        RiskFactor hostile = new RiskFactor(
            RiskFactorCode.VELOCITY_ANOMALY, RiskFactorCategory.BEHAVIOR, RiskFactorSeverity.HIGH,
            1.0, 1.0, "requestsPerWindow", "hostile", "test");
        SecurityAdvice advice = new SecurityAdvice(
            AdvisoryCode.RELEASE_QUARANTINE_AFTER_REVIEW, AdvisoryPriority.HIGH, "hostile",
            List.of(RiskFactorCode.VELOCITY_ANOMALY), true);
        RiskDecision mutated = decision.withExplanation(new RiskExplanation(List.of(hostile), advice));
        assertEquals(EnforcementAction.MONITOR, mutated.action());
    }

    @Test
    void advisoryPriorityCannotDirectlyTriggerBlock() {
        PolicyEngine policy = new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8);
        SentinelDecisionEngine engine = engine(new FixedScorer(0.1), policy, e -> {}, false);
        RiskDecision decision = engine.evaluate(mockRequest(), "idhash", features(), new RequestContext());
        assertEquals(EnforcementAction.ALLOW, decision.action());
        SecurityAdvice advice = new SecurityAdvice(
            AdvisoryCode.REQUIRE_ADDITIONAL_VERIFICATION, AdvisoryPriority.HIGH, "hostile",
            List.of(), true);
        assertEquals(EnforcementAction.ALLOW,
            decision.withExplanation(new RiskExplanation(List.of(), advice)).action());
    }

    @Test
    void invalidScorePathProducesHealthAdviceNotAttack() {
        AtomicReference<TelemetryEvent> threat = new AtomicReference<>();
        SentinelDecisionEngine engine = engine(new FixedScorer(Double.POSITIVE_INFINITY), new ThresholdPolicyEngine(), e -> {
            if ("ThreatScored".equals(e.type())) {
                threat.set(e);
            }
        }, false);
        RiskDecision decision = engine.evaluate(mockRequest(), "abcdefghijkl", features(), new RequestContext());
        assertTrue(decision.hasStatus(EvaluationStatus.INVALID_SCORE));
        assertEquals(EnforcementAction.ALLOW, decision.action());
        assertTrue(decision.explanation().factors().stream()
            .anyMatch(f -> f.code() == RiskFactorCode.INVALID_SCORE_SIGNAL));
        assertTrue(decision.explanation().factors().stream()
            .noneMatch(f -> f.category() == RiskFactorCategory.BEHAVIOR));
        assertEquals(AdvisoryCode.REVIEW_SCORER_HEALTH, decision.explanation().advice().code());
        assertNotNull(threat.get());
        assertEquals("REVIEW_SCORER_HEALTH", threat.get().payload().get("advisoryCode"));
        assertFalse(String.valueOf(threat.get().payload().get("identityHash")).contains("abcdefghijkl"));
    }

    @Test
    void monitorAndEnforcePathsStillHonorPolicyBands() {
        PolicyEngine policy = new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8);
        assertEquals(EnforcementAction.MONITOR,
            engine(new FixedScorer(0.35), policy, e -> {}, false)
                .evaluate(mockRequest(), "id", features(), new RequestContext()).action());
        assertEquals(EnforcementAction.BLOCK,
            engine(new FixedScorer(0.7), policy, e -> {}, false)
                .evaluate(mockRequest(), "id", features(), new RequestContext()).action());
        assertEquals(EnforcementAction.QUARANTINE,
            engine(new FixedScorer(0.9), policy, e -> {}, false)
                .evaluate(mockRequest(), "id", features(), new RequestContext()).action());
    }

    @Test
    void existingQuarantineStillAuthoritativeOnInvalidScore() {
        SentinelDecisionEngine engine = engine(new FixedScorer(Double.NaN), new ThresholdPolicyEngine(), e -> {}, true);
        RiskDecision decision = engine.evaluate(mockRequest(), "id", features(), new RequestContext());
        assertEquals(EnforcementAction.QUARANTINE, decision.action());
        assertEquals(AdvisoryCode.RELEASE_QUARANTINE_AFTER_REVIEW, decision.explanation().advice().code());
    }

    @Test
    void telemetryOmitsHighCardinalityDescriptions() {
        List<TelemetryEvent> events = new ArrayList<>();
        SentinelDecisionEngine engine = engine(new FixedScorer(0.55), new ThresholdPolicyEngine(), events::add, false);
        engine.evaluate(mockRequest(), "idhash123456", features(), new RequestContext());
        TelemetryEvent threat = events.stream().filter(e -> "ThreatScored".equals(e.type())).findFirst().orElseThrow();
        assertTrue(threat.payload().containsKey("factorCount"));
        assertFalse(threat.payload().containsKey("explanation"));
        Object hash = threat.payload().get("identityHash");
        assertFalse(String.valueOf(hash).contains("idhash123456"));
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer,
                                                 PolicyEngine policy,
                                                 TelemetryEmitter telemetry,
                                                 boolean quarantined) {
        EnforcementHandler handler = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                throw new AssertionError("engine must not apply enforcement");
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return quarantined;
            }
        };
        return new SentinelDecisionEngine(
            scorer,
            policy,
            handler,
            telemetry,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE);
    }

    private static HttpRequestView mockRequest() {
        HttpRequestView req = mock(HttpRequestView.class);
        when(req.getRequestURI()).thenReturn("/api");
        when(req.getMethod()).thenReturn("GET");
        return req;
    }

    private static RequestFeatures features() {
        return RequestFeatures.builder()
            .identityHash("idhash")
            .endpoint("/api")
            .timestampMillis(System.currentTimeMillis())
            .requestsPerWindow(3)
            .endpointEntropy(0.2)
            .endpointConcentration(0.2)
            .tokenAgeSeconds(-1)
            .parameterCount(2)
            .payloadSizeBytes(32)
            .headerFingerprintHash(99L)
            .ipBucket(1)
            .build();
    }

    private static final class FixedScorer implements AnomalyScorer {
        private final double score;

        private FixedScorer(double score) {
            this.score = score;
        }

        @Override
        public double score(RequestFeatures features) {
            return score;
        }

        @Override
        public void update(RequestFeatures features) {
        }
    }

    private static final class CapturingPolicyEngine implements PolicyEngine {
        private final EnforcementAction action;
        private int calls;

        private CapturingPolicyEngine(EnforcementAction action) {
            this.action = action;
        }

        @Override
        public EnforcementAction evaluate(double riskScore, RequestFeatures features, String endpoint) {
            calls++;
            return action;
        }
    }
}
