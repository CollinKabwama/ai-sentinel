package io.aisentinel.core.identity.model;

/**
 * Identity-centric view for a single request, carried in {@link io.aisentinel.core.model.RequestContext}.
 * <ul>
 *   <li><strong>Feature off</strong> — {@link io.aisentinel.core.identity.spi.NoopIdentityContextResolver}: key absent.</li>
 *   <li><strong>Feature on</strong> — resolver stores this record: unauthenticated users still get explicit
 *       {@link AuthenticationContext} (and {@link SessionContext}); see {@link AuthenticationContext#authenticationInfrastructurePresent()}.</li>
 * </ul>
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
