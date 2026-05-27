package io.aisentinel.core.policy;

import io.aisentinel.core.identity.IdentityContextKeys;
import io.aisentinel.core.identity.model.AuthenticationContext;
import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * Threshold-based trust policy: escalates {@link EnforcementAction} only (never relaxes anomaly policy).
 */
public final class DefaultTrustPolicyAdjuster implements TrustPolicyAdjuster {

    private final TrustPolicyConfig config;

    public DefaultTrustPolicyAdjuster(TrustPolicyConfig config) {
        this.config = config != null ? config : TrustPolicyConfig.disabled();
    }

    @Override
    public TrustPolicyAdjustment adjust(EnforcementAction baseline,
                                        double riskScore,
                                        RequestFeatures features,
                                        String endpoint,
                                        HttpServletRequest request,
                                        RequestContext ctx) {
        if (!config.enabled()) {
            return new TrustPolicyAdjustment(baseline, "");
        }
        IdentityContext identity = ctx != null
            ? ctx.get(IdentityContextKeys.IDENTITY_CONTEXT, IdentityContext.class)
            : null;
        if (identity == null) {
            return new TrustPolicyAdjustment(baseline, "");
        }
        if (config.authenticatedOnly()) {
            AuthenticationContext auth = identity.authentication();
            if (!auth.authenticated() || auth.anonymous()) {
                return new TrustPolicyAdjustment(baseline, "");
            }
        }
        return escalateGivenTrust(identity.trust().value(), baseline, riskScore, features, endpoint, request);
    }

    /**
     * Trust-band escalation after identity gates; package-private for tests (e.g. raw {@code NaN} without
     * {@link io.aisentinel.core.identity.model.TrustScore} canonicalization).
     */
    TrustPolicyAdjustment escalateGivenTrust(double trust,
                                             EnforcementAction baseline,
                                             double riskScore,
                                             RequestFeatures features,
                                             String endpoint,
                                             HttpServletRequest request) {
        if (Double.isNaN(trust)) {
            return new TrustPolicyAdjustment(baseline, "");
        }
        String method = request != null && request.getMethod() != null ? request.getMethod().toUpperCase(Locale.ROOT) : "";
        boolean protectedEp = isProtectedEndpoint(endpoint != null ? endpoint : "", method);

        EnforcementAction floor = trustFloor(trust, protectedEp, riskScore);
        if (floor == null) {
            return new TrustPolicyAdjustment(baseline, "");
        }
        EnforcementAction merged = maxSeverity(baseline, floor);
        if (merged == baseline) {
            return new TrustPolicyAdjustment(baseline, "");
        }
        String detail = "trust-policy:trust=" + round4(trust)
            + ";floor=" + floor
            + ";protected=" + protectedEp
            + ";risk=" + round4(riskScore)
            + ";merged=" + merged;
        return new TrustPolicyAdjustment(merged, detail);
    }

    private EnforcementAction trustFloor(double trust, boolean protectedEndpoint, double riskScore) {
        if (trust >= config.trustNoEffectMinimum()) {
            return null;
        }
        if (trust >= config.trustMediumBandMinimum()) {
            return EnforcementAction.MONITOR;
        }
        if (trust >= config.trustLowBandMinimum()) {
            return protectedEndpoint ? EnforcementAction.THROTTLE : EnforcementAction.MONITOR;
        }
        if (protectedEndpoint && config.denyOnCriticalTrustEnabled()) {
            boolean riskOk = !config.requireMinRiskForTrustDeny()
                || (!Double.isNaN(riskScore) && riskScore >= config.minRiskScoreForTrustDeny());
            if (riskOk) {
                return EnforcementAction.BLOCK;
            }
        }
        return protectedEndpoint ? EnforcementAction.THROTTLE : EnforcementAction.MONITOR;
    }

    private static EnforcementAction maxSeverity(EnforcementAction a, EnforcementAction b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private boolean isProtectedEndpoint(String endpoint, String methodUpper) {
        if (config.protectedEndpointPatterns().isEmpty()) {
            return false;
        }
        if (!config.httpMethodsUpper().isEmpty() && !config.httpMethodsUpper().contains(methodUpper)) {
            return false;
        }
        for (String pattern : config.protectedEndpointPatterns()) {
            if (matchesPattern(endpoint, pattern)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesPattern(String endpoint, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return false;
        }
        String p = pattern.trim();
        if (p.equals("/**") || p.equals("*")) {
            return true;
        }
        if (p.endsWith("/**")) {
            String base = p.substring(0, p.length() - 3);
            return endpoint.equals(base) || endpoint.startsWith(base + "/");
        }
        if (p.endsWith("*") && p.length() > 1 && !p.endsWith("**")) {
            String prefix = p.substring(0, p.length() - 1);
            return endpoint.startsWith(prefix);
        }
        return endpoint.equals(p);
    }

    private static String round4(double v) {
        return Double.isNaN(v) ? "NaN" : String.format(Locale.ROOT, "%.4f", v);
    }
}
