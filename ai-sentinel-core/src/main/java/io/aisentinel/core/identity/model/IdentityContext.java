package io.aisentinel.core.identity.model;

/**
 * Identity-centric view for a single request, carried in {@link io.aisentinel.core.model.RequestContext}.
 */
public record IdentityContext(
    AuthenticationContext authentication,
    SessionContext session,
    TrustScore trust,
    IdentityRiskSignals riskSignals
) {
    public IdentityContext {
        authentication = authentication != null ? authentication : AuthenticationContext.unauthenticated();
        session = session != null ? session : SessionContext.none();
        trust = trust != null ? trust : TrustScore.fullyTrusted();
        riskSignals = riskSignals != null ? riskSignals : IdentityRiskSignals.empty();
    }

    public IdentityContext withTrust(TrustScore newTrust) {
        return new IdentityContext(authentication, session, newTrust, riskSignals);
    }
}
