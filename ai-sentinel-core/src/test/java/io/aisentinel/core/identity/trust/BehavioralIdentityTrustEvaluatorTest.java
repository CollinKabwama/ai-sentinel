package io.aisentinel.core.identity.trust;

import io.aisentinel.core.identity.IdentityRiskSignalKeys;
import io.aisentinel.core.identity.model.AuthenticationContext;
import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.IdentityRiskSignals;
import io.aisentinel.core.identity.model.SessionContext;
import io.aisentinel.core.identity.model.TrustEvaluation;
import io.aisentinel.core.identity.model.TrustScore;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BehavioralIdentityTrustEvaluatorTest {

    private static RequestFeatures features(String endpoint, double rpw, long headerHash, int ipBucket, long ts) {
        return RequestFeatures.builder()
            .identityHash("ih1")
            .endpoint(endpoint)
            .timestampMillis(ts)
            .requestsPerWindow(rpw)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(headerHash)
            .ipBucket(ipBucket)
            .build();
    }

    @Test
    void repeatedStableBehaviorYieldsHighTrustAfterWarmup() {
        IdentityBehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMinutes(15), 10_000);
        BehavioralIdentityTrustEvaluator ev = new BehavioralIdentityTrustEvaluator(store, 25.0, 0.82, 0.75);
        IdentityContext id = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.ofHashedId("sess-stable"),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty());
        HttpServletRequest req = mock(HttpServletRequest.class);
        RequestContext ctx = new RequestContext();
        TrustEvaluation third = null;
        for (int i = 0; i < 3; i++) {
            third = ev.evaluate(id, req, features("/api/v1", 2.0, 42L, 7, 1_700_000_000_000L + i * 1000L), ctx);
        }
        assertThat(third).isNotNull();
        assertThat(third.trustScore().value()).isGreaterThan(0.95);
        assertThat(third.riskSignals().components()).doesNotContainKey(IdentityRiskSignalKeys.SPARSE_HISTORY);
    }

    @Test
    void sparseHistoryIsCautiousAndExplained() {
        IdentityBehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMinutes(15), 10_000);
        BehavioralIdentityTrustEvaluator ev = new BehavioralIdentityTrustEvaluator(store, 25.0, 0.82, 0.75);
        IdentityContext id = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.ofHashedId("sess-sparse"),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty());
        TrustEvaluation first = ev.evaluate(id, mock(HttpServletRequest.class),
            features("/a", 1.0, 1L, 0, 1L), new RequestContext());
        assertThat(first.riskSignals().components()).containsKey(IdentityRiskSignalKeys.SPARSE_HISTORY);
        assertThat(first.trustScore().value()).isLessThanOrEqualTo(0.82);
    }

    @Test
    void requestBurstAddsSignal() {
        IdentityBehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMinutes(15), 10_000);
        BehavioralIdentityTrustEvaluator ev = new BehavioralIdentityTrustEvaluator(store, 25.0, 0.82, 0.75);
        IdentityContext id = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.ofHashedId("sess-burst"),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty());
        TrustEvaluation te = ev.evaluate(id, mock(HttpServletRequest.class),
            features("/x", 80.0, 1L, 0, 1L), new RequestContext());
        assertThat(te.riskSignals().components()).containsKey(IdentityRiskSignalKeys.REQUEST_BURST);
        assertThat(te.trustScore().value()).isLessThan(0.95);
    }

    @Test
    void ipDriftAddsSignalWhenComparable() {
        IdentityBehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMinutes(15), 10_000);
        BehavioralIdentityTrustEvaluator ev = new BehavioralIdentityTrustEvaluator(store, 25.0, 0.82, 0.75);
        IdentityContext id = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.ofHashedId("sess-ip"),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty());
        HttpServletRequest req = mock(HttpServletRequest.class);
        RequestContext ctx = new RequestContext();
        ev.evaluate(id, req, features("/p", 1.0, 1L, 3, 1L), ctx);
        TrustEvaluation second = ev.evaluate(id, req, features("/p", 1.0, 1L, 99, 2L), ctx);
        assertThat(second.riskSignals().components()).containsKey(IdentityRiskSignalKeys.IP_DRIFT);
    }

    @Test
    void userAgentDriftAddsSignalWhenComparable() {
        IdentityBehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMinutes(15), 10_000);
        BehavioralIdentityTrustEvaluator ev = new BehavioralIdentityTrustEvaluator(store, 25.0, 0.82, 0.75);
        IdentityContext id = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.ofHashedId("sess-ua"),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty());
        HttpServletRequest req = mock(HttpServletRequest.class);
        RequestContext ctx = new RequestContext();
        ev.evaluate(id, req, features("/p", 1.0, 100L, 0, 1L), ctx);
        TrustEvaluation second = ev.evaluate(id, req, features("/p", 1.0, 200L, 0, 2L), ctx);
        assertThat(second.riskSignals().components()).containsKey(IdentityRiskSignalKeys.USER_AGENT_DRIFT);
    }

    @Test
    void newSessionContributesSignal() {
        IdentityBehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMinutes(15), 10_000);
        BehavioralIdentityTrustEvaluator ev = new BehavioralIdentityTrustEvaluator(store, 25.0, 0.82, 0.75);
        IdentityContext id = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.ofHashedId("new-s", true),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty());
        TrustEvaluation te = ev.evaluate(id, mock(HttpServletRequest.class),
            features("/n", 1.0, 1L, 0, 1L), new RequestContext());
        assertThat(te.riskSignals().components()).containsKey(IdentityRiskSignalKeys.NEW_SESSION);
    }

    @Test
    void baselineKeyPrefersPrincipalThenSessionThenIdentityHash() {
        assertThat(BehavioralIdentityTrustEvaluator.baselineKey(
            new IdentityContext(
                AuthenticationContext.ofPrincipal("alice"),
                SessionContext.ofHashedId("sh1"),
                TrustScore.fullyTrusted(),
                IdentityRiskSignals.empty()),
            "fallback")).isEqualTo("p:alice");
        assertThat(BehavioralIdentityTrustEvaluator.baselineKey(
            new IdentityContext(
                AuthenticationContext.ofPrincipal("alice"),
                SessionContext.none(),
                TrustScore.fullyTrusted(),
                IdentityRiskSignals.empty()),
            "fallback")).isEqualTo("p:alice");
        assertThat(BehavioralIdentityTrustEvaluator.baselineKey(
            new IdentityContext(
                AuthenticationContext.unauthenticated(),
                SessionContext.ofHashedId("anon-sess"),
                TrustScore.fullyTrusted(),
                IdentityRiskSignals.empty()),
            "ihx")).isEqualTo("s:anon-sess");
        assertThat(BehavioralIdentityTrustEvaluator.baselineKey(
            new IdentityContext(
                AuthenticationContext.unauthenticated(),
                SessionContext.none(),
                TrustScore.fullyTrusted(),
                IdentityRiskSignals.empty()),
            "ihx")).isEqualTo("i:ihx");
    }

    @Test
    void combinedSignalsPenaltyIsCappedAndTrustBounded() {
        IdentityBehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMinutes(15), 10_000);
        double maxTotalPenalty = 0.75;
        BehavioralIdentityTrustEvaluator ev = new BehavioralIdentityTrustEvaluator(store, 25.0, 0.82, maxTotalPenalty);
        IdentityContext id = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.ofHashedId("combo", true),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty());
        TrustEvaluation te = ev.evaluate(id, mock(HttpServletRequest.class),
            features("/p", 80.0, 1L, 0, 1L), new RequestContext());
        var c = te.riskSignals().components();
        assertThat(c).containsKeys(
            IdentityRiskSignalKeys.SPARSE_HISTORY,
            IdentityRiskSignalKeys.NEW_SESSION,
            IdentityRiskSignalKeys.REQUEST_BURST);
        double rawSum = c.values().stream().mapToDouble(Double::doubleValue).sum();
        assertThat(rawSum).isGreaterThan(maxTotalPenalty);
        assertThat(te.trustScore().value()).isGreaterThanOrEqualTo(0.12);
        assertThat(te.trustScore().value()).isEqualTo(0.25);
    }
}
