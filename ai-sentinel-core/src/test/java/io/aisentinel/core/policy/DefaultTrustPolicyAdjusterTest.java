package io.aisentinel.core.policy;

import io.aisentinel.core.identity.IdentityContextKeys;
import io.aisentinel.core.identity.model.AuthenticationContext;
import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.IdentityRiskSignals;
import io.aisentinel.core.identity.model.SessionContext;
import io.aisentinel.core.identity.model.TrustScore;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultTrustPolicyAdjusterTest {

    private static RequestFeatures features(String endpoint) {
        return RequestFeatures.builder()
            .identityHash("h")
            .endpoint(endpoint)
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

    private static RequestContext ctx(IdentityContext ic) {
        RequestContext c = new RequestContext();
        if (ic != null) {
            c.put(IdentityContextKeys.IDENTITY_CONTEXT, ic);
        }
        return c;
    }

    private static IdentityContext authUser(double trust) {
        return new IdentityContext(
            AuthenticationContext.ofPrincipal("alice"),
            SessionContext.none(),
            new TrustScore(trust, "t"),
            IdentityRiskSignals.empty());
    }

    private static HttpServletRequest post(String path) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getMethod()).thenReturn("POST");
        return r;
    }

    @Test
    void disabledLeavesBaselineUnchanged() {
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(TrustPolicyConfig.disabled());
        TrustPolicyAdjustment out = adj.adjust(EnforcementAction.ALLOW, 0.1, features("/x"), "/x",
            post("/x"), ctx(authUser(0.1)));
        assertThat(out.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(out.trustPolicyDetail()).isEmpty();
    }

    @Test
    void highTrustNoEffect() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of("/api/admin/**"), Set.of(),
            0.80, 0.50, 0.25, true, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        TrustPolicyAdjustment out = adj.adjust(EnforcementAction.ALLOW, 0.9, features("/api/admin/p"), "/api/admin/p",
            post("/api/admin/p"), ctx(authUser(0.85)));
        assertThat(out.action()).isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void mediumTrustEscalatesToMonitor() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of(), Set.of(),
            0.80, 0.50, 0.25, false, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        TrustPolicyAdjustment out = adj.adjust(EnforcementAction.ALLOW, 0.05, features("/public"), "/public",
            post("/public"), ctx(authUser(0.60)));
        assertThat(out.action()).isEqualTo(EnforcementAction.MONITOR);
        assertThat(out.trustPolicyDetail()).contains("trust-policy:");
    }

    @Test
    void lowTrustOnUnprotectedOnlyMonitor() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of("/api/admin/**"), Set.of(),
            0.80, 0.50, 0.25, true, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        TrustPolicyAdjustment out = adj.adjust(EnforcementAction.ALLOW, 0.05, features("/other"), "/other",
            post("/other"), ctx(authUser(0.40)));
        assertThat(out.action()).isEqualTo(EnforcementAction.MONITOR);
    }

    @Test
    void lowTrustOnProtectedThrottle() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of("/api/admin/**"), Set.of(),
            0.80, 0.50, 0.25, false, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        TrustPolicyAdjustment out = adj.adjust(EnforcementAction.ALLOW, 0.05, features("/api/admin/x"), "/api/admin/x",
            post("/api/admin/x"), ctx(authUser(0.40)));
        assertThat(out.action()).isEqualTo(EnforcementAction.THROTTLE);
    }

    @Test
    void criticalOnProtectedDenyWhenRiskSatisfied() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of("/api/admin/**"), Set.of(),
            0.80, 0.50, 0.25, true, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        TrustPolicyAdjustment out = adj.adjust(EnforcementAction.ALLOW, 0.55, features("/api/admin/x"), "/api/admin/x",
            post("/api/admin/x"), ctx(authUser(0.10)));
        assertThat(out.action()).isEqualTo(EnforcementAction.BLOCK);
        assertThat(out.trustPolicyDetail()).contains("merged=BLOCK");
    }

    @Test
    void criticalDenyRequiresRiskWhenConfigured() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of("/api/admin/**"), Set.of(),
            0.80, 0.50, 0.25, true, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        TrustPolicyAdjustment out = adj.adjust(EnforcementAction.ALLOW, 0.05, features("/api/admin/x"), "/api/admin/x",
            post("/api/admin/x"), ctx(authUser(0.10)));
        assertThat(out.action()).isEqualTo(EnforcementAction.THROTTLE);
    }

    @Test
    void missingIdentityNoChange() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of("/api/**"), Set.of(),
            0.80, 0.50, 0.25, true, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        TrustPolicyAdjustment out = adj.adjust(EnforcementAction.ALLOW, 0.99, features("/api/x"), "/api/x",
            post("/api/x"), ctx(null));
        assertThat(out.action()).isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void authenticatedOnlySkipsAnonymous() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of("/api/**"), Set.of(),
            0.80, 0.50, 0.25, true, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        IdentityContext anon = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.none(),
            new TrustScore(0.05, "t"),
            IdentityRiskSignals.empty());
        TrustPolicyAdjustment out = adj.adjust(EnforcementAction.ALLOW, 0.99, features("/api/x"), "/api/x",
            post("/api/x"), ctx(anon));
        assertThat(out.action()).isEqualTo(EnforcementAction.ALLOW);
    }

    @Test
    void neverRelaxesBaseline() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of(), Set.of(),
            0.80, 0.50, 0.25, false, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        TrustPolicyAdjustment out = adj.adjust(EnforcementAction.BLOCK, 0.05, features("/x"), "/x",
            post("/x"), ctx(authUser(0.99)));
        assertThat(out.action()).isEqualTo(EnforcementAction.BLOCK);
    }

    @Test
    void httpMethodScopeRestrictsProtectedMatch() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of("/api/admin/**"), Set.of("GET"),
            0.80, 0.50, 0.25, true, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        TrustPolicyAdjustment postNotProtected = adj.adjust(EnforcementAction.ALLOW, 0.99, features("/api/admin/x"),
            "/api/admin/x", post("/api/admin/x"), ctx(authUser(0.10)));
        assertThat(postNotProtected.action()).isEqualTo(EnforcementAction.MONITOR);

        HttpServletRequest get = mock(HttpServletRequest.class);
        when(get.getMethod()).thenReturn("GET");
        TrustPolicyAdjustment getDenied = adj.adjust(EnforcementAction.ALLOW, 0.99, features("/api/admin/x"),
            "/api/admin/x", get, ctx(authUser(0.10)));
        assertThat(getDenied.action()).isEqualTo(EnforcementAction.BLOCK);
    }

    @Test
    void matchesPatternSubtree() {
        assertThat(DefaultTrustPolicyAdjuster.matchesPattern("/api/admin/users", "/api/admin/**")).isTrue();
        assertThat(DefaultTrustPolicyAdjuster.matchesPattern("/api/other", "/api/admin/**")).isFalse();
        assertThat(DefaultTrustPolicyAdjuster.matchesPattern("/api/admin", "/api/admin/**")).isTrue();
    }

    @Test
    void nanTrustLeavesBaselineUnchanged() {
        TrustPolicyConfig cfg = new TrustPolicyConfig(
            true, true, List.of("/api/**"), Set.of(),
            0.80, 0.50, 0.25, true, true, 0.40);
        DefaultTrustPolicyAdjuster adj = new DefaultTrustPolicyAdjuster(cfg);
        TrustPolicyAdjustment out = adj.escalateGivenTrust(
            Double.NaN,
            EnforcementAction.ALLOW,
            0.99,
            features("/api/x"),
            "/api/x",
            post("/api/x"));
        assertThat(out.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(out.trustPolicyDetail()).isEmpty();
    }
}
