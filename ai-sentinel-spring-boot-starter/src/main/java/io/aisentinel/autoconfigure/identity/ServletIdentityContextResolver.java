package io.aisentinel.autoconfigure.identity;

import io.aisentinel.core.identity.IdentityContextKeys;
import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.IdentityRiskSignals;
import io.aisentinel.core.identity.model.TrustScore;
import io.aisentinel.core.identity.spi.AuthenticationInspector;
import io.aisentinel.core.identity.spi.IdentityContextResolver;
import io.aisentinel.core.identity.spi.SessionInspector;
import io.aisentinel.core.model.RequestContext;
import jakarta.servlet.http.HttpServletRequest;

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
    public void resolve(HttpServletRequest request, String identityHash, RequestContext ctx) {
        var auth = authenticationInspector.inspect(request, identityHash);
        var session = sessionInspector.inspect(request, identityHash);
        var identity = new IdentityContext(auth, session, TrustScore.fullyTrusted(), IdentityRiskSignals.empty());
        ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, identity);
    }
}
