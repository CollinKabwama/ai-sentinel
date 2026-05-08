package io.aisentinel.core.identity.spi;

import io.aisentinel.core.identity.model.AuthenticationContext;
import io.aisentinel.core.identity.model.SessionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Minimal contract tests for {@link SessionInspector} and {@link AuthenticationInspector} as functional SPIs.
 */
class IdentitySpiFunctionalContractsTest {

    @Test
    void sessionInspectorLambdaReturnsSessionContext() {
        SessionInspector inspector = (request, identityHash) -> SessionContext.ofHashedId("deadbeef", true);

        HttpServletRequest request = mock(HttpServletRequest.class);
        SessionContext ctx = inspector.inspect(request, "identityhash1");

        assertThat(ctx.present()).isTrue();
        assertThat(ctx.sessionIdHash()).isEqualTo("deadbeef");
        assertThat(ctx.newSession()).isTrue();
    }

    @Test
    void authenticationInspectorLambdaReturnsAuthenticationContext() {
        AuthenticationInspector inspector =
            (request, identityHash) -> AuthenticationContext.unauthenticated(false);

        HttpServletRequest request = mock(HttpServletRequest.class);
        AuthenticationContext auth = inspector.inspect(request, "identityhash1");

        assertThat(auth.authenticated()).isFalse();
        assertThat(auth.authenticationInfrastructurePresent()).isFalse();
    }
}
