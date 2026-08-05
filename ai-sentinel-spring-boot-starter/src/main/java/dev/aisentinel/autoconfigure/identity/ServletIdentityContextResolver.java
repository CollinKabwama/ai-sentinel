package dev.aisentinel.autoconfigure.identity;

import dev.aisentinel.core.identity.IdentityContextKeys;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.IdentityRiskSignals;
import dev.aisentinel.core.identity.model.TrustScore;
import dev.aisentinel.core.identity.spi.AuthenticationInspector;
import dev.aisentinel.core.identity.spi.IdentityContextResolver;
import dev.aisentinel.core.identity.spi.SessionInspector;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.model.RequestContext;

/**
 * Assembles a normalized {@link IdentityContext} from {@link AuthenticationInspector} and {@link SessionInspector}
 * (no policy or scoring side effects). Fails only if a delegate throws; the pipeline treats that as fail-open.
 */
public final class ServletIdentityContextResolver implements IdentityContextResolver {

    private final AuthenticationInspector authenticationInspector;
    private final SessionInspector sessionInspector;

    public ServletIdentityContextResolver(AuthenticationInspector authenticationInspector,
                                        SessionInspector sessionInspector) {
        this.authenticationInspector = authenticationInspector;
        this.sessionInspector = sessionInspector;
    }

    @Override
    public void resolve(HttpRequestView request, String identityHash, RequestContext ctx) {
        var auth = authenticationInspector.inspect(request, identityHash);
        var session = sessionInspector.inspect(request, identityHash);
        var identity = new IdentityContext(auth, session, TrustScore.fullyTrusted(), IdentityRiskSignals.empty());
        ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, identity);
    }
}
