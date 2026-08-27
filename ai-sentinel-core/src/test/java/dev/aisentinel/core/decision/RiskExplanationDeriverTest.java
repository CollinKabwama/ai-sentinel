package dev.aisentinel.core.decision;

import dev.aisentinel.core.identity.IdentityContextKeys;
import dev.aisentinel.core.identity.IdentityRiskSignalKeys;
import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.IdentityRiskSignals;
import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.identity.model.TrustScore;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.scoring.StatisticalScoreSnapshot;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskExplanationDeriverTest {

    @Test
    void invalidScoreDoesNotProduceBehavioralAttackFactor() {
        RiskExplanation explanation = RiskExplanationDeriver.derive(
            EnforcementAction.ALLOW,
            Set.of(EvaluationStatus.INVALID_SCORE),
            evidenceWithDominant("requestsPerWindow", 5.0),
            null,
            Double.NaN,
            Double.NaN);

        assertTrue(explanation.factors().stream().anyMatch(f -> f.code() == RiskFactorCode.INVALID_SCORE_SIGNAL));
        assertTrue(explanation.factors().stream().noneMatch(f -> f.category() == RiskFactorCategory.BEHAVIOR));
        assertEquals(AdvisoryCode.REVIEW_SCORER_HEALTH, explanation.advice().code());
    }

    @Test
    void degradedDoesNotProduceMaliciousBehaviorFactorAlone() {
        RiskExplanation explanation = RiskExplanationDeriver.derive(
            EnforcementAction.ALLOW,
            Set.of(EvaluationStatus.DEGRADED),
            null,
            null,
            0.1,
            0.1);

        assertTrue(explanation.factors().stream().anyMatch(f -> f.code() == RiskFactorCode.PIPELINE_DEGRADED));
        assertTrue(explanation.factors().stream().noneMatch(f -> f.category() == RiskFactorCategory.BEHAVIOR));
        assertEquals(AdvisoryCode.OTHER_OPERATOR_REVIEW, explanation.advice().code());
    }

    @Test
    void duplicateFactorsDedupByCodeKeepingHigherSeverity() {
        RequestContext ctx = trustContext(0.9, Map.of(
            IdentityRiskSignalKeys.REQUEST_BURST, 0.9
        ));
        RiskExplanation explanation = RiskExplanationDeriver.derive(
            EnforcementAction.MONITOR,
            Set.of(EvaluationStatus.STATISTICAL_LIVE, EvaluationStatus.COMPLETE),
            evidenceWithDominant("requestsPerWindow", 4.0),
            ctx,
            0.7,
            0.7);

        long velocity = explanation.factors().stream()
            .filter(f -> f.code() == RiskFactorCode.VELOCITY_ANOMALY)
            .count();
        assertEquals(1L, velocity);
    }

    @Test
    void factorOrderingIsDeterministic() {
        RiskExplanation a = multiFactorExplanation();
        RiskExplanation b = multiFactorExplanation();
        assertEquals(a.factors().stream().map(RiskFactor::code).toList(),
            b.factors().stream().map(RiskFactor::code).toList());
        assertTrue(severityRank(a.factors().get(0).severity())
            >= severityRank(a.factors().get(a.factors().size() - 1).severity()));
    }

    @Test
    void unknownTrustSignalKeyIgnored() {
        RequestContext ctx = trustContext(0.9, Map.of("totally_unknown_signal", 1.0));
        RiskExplanation explanation = RiskExplanationDeriver.derive(
            EnforcementAction.ALLOW,
            Set.of(EvaluationStatus.COMPLETE),
            null,
            ctx,
            0.1,
            0.1);
        assertTrue(explanation.factors().stream()
            .noneMatch(f -> "totally_unknown_signal".equals(f.evidenceRef())));
    }

    @Test
    void emptyFactorListAndNullAdviceSafe() {
        RiskExplanation empty = RiskExplanation.empty();
        assertTrue(empty.factors().isEmpty());
        assertNull(empty.advice());
        assertTrue(empty.isEmpty());
    }

    @Test
    void nonFiniteContributionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RiskFactor(
            RiskFactorCode.BEHAVIOR_DEVIATION,
            RiskFactorCategory.BEHAVIOR,
            RiskFactorSeverity.HIGH,
            Double.NaN,
            0.5,
            "x",
            "y",
            "z"));
    }

    @Test
    void positiveBehaviorFactorFromDominantFeature() {
        RiskExplanation explanation = RiskExplanationDeriver.derive(
            EnforcementAction.BLOCK,
            Set.of(EvaluationStatus.STATISTICAL_LIVE, EvaluationStatus.COMPLETE),
            evidenceWithDominant("parameterCount", 3.5),
            null,
            0.85,
            0.85);
        assertTrue(explanation.factors().stream().anyMatch(f -> f.code() == RiskFactorCode.BEHAVIOR_DEVIATION));
        assertEquals(AdvisoryCode.INVESTIGATE, explanation.advice().code());
    }

    @Test
    void trustFactorFromDegradedTrustScore() {
        RequestContext ctx = trustContext(0.2, Map.of());
        RiskExplanation explanation = RiskExplanationDeriver.derive(
            EnforcementAction.MONITOR,
            Set.of(EvaluationStatus.COMPLETE),
            null,
            ctx,
            0.3,
            0.3);
        assertTrue(explanation.factors().stream().anyMatch(f -> f.code() == RiskFactorCode.TRUST_DEGRADATION));
    }

    @Test
    void adviceLinksFactorCodes() {
        RiskExplanation explanation = RiskExplanationDeriver.derive(
            EnforcementAction.QUARANTINE,
            Set.of(EvaluationStatus.COMPLETE),
            evidenceWithDominant("requestsPerWindow", 5.0),
            null,
            0.95,
            0.95);
        assertEquals(AdvisoryCode.RELEASE_QUARANTINE_AFTER_REVIEW, explanation.advice().code());
        assertFalse(explanation.advice().linkedFactorCodes().isEmpty());
    }

    @Test
    void riskDecisionWithExplanationDoesNotChangeAction() {
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("hash")
            .endpoint("/api")
            .timestampMillis(System.currentTimeMillis())
            .requestsPerWindow(1)
            .endpointEntropy(0.1)
            .endpointConcentration(0.1)
            .tokenAgeSeconds(-1)
            .parameterCount(1)
            .payloadSizeBytes(10)
            .headerFingerprintHash(1L)
            .ipBucket(1)
            .build();
        RiskDecision base = new RiskDecision(
            EnforcementAction.THROTTLE, 0.5, 0.5, features, new RequestContext(), false,
            Set.of(EvaluationStatus.COMPLETE));
        RiskExplanation explanation = RiskExplanationDeriver.derive(
            base.action(), base.evaluationStatuses(), evidenceWithDominant("requestsPerWindow", 4.0),
            null, 0.5, 0.5);
        RiskDecision with = base.withExplanation(explanation);
        assertEquals(base.action(), with.action());
        assertEquals(base.anomalyScore(), with.anomalyScore());
        assertEquals(base.policyScore(), with.policyScore());
        assertFalse(with.explanation().factors().isEmpty());
    }

    private static RiskExplanation multiFactorExplanation() {
        RequestContext ctx = trustContext(0.3, Map.of(
            IdentityRiskSignalKeys.IP_DRIFT, 0.5,
            IdentityRiskSignalKeys.NEW_SESSION, 0.2
        ));
        return RiskExplanationDeriver.derive(
            EnforcementAction.MONITOR,
            EnumSet.of(EvaluationStatus.STATISTICAL_LIVE, EvaluationStatus.COMPLETE, EvaluationStatus.DEGRADED),
            evidenceWithDominant("endpointEntropy", 2.0),
            ctx,
            0.6,
            0.6);
    }

    private static DecisionExplanationEvidence evidenceWithDominant(String feature, double cappedAbsZ) {
        StatisticalScoreSnapshot snap = new StatisticalScoreSnapshot(
            0.7, false, feature, 10.0, 1.0, 1.0, cappedAbsZ, cappedAbsZ);
        return DecisionExplanationEvidence.fromStatistical(0.7, snap);
    }

    private static RequestContext trustContext(double trust, Map<String, Double> signals) {
        RequestContext ctx = new RequestContext();
        Map<String, Double> copy = new LinkedHashMap<>(signals);
        ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.none(),
            new TrustScore(trust, "test"),
            new IdentityRiskSignals(copy)));
        return ctx;
    }

    private static int severityRank(RiskFactorSeverity severity) {
        return severity.ordinal();
    }
}
