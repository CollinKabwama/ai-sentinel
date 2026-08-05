package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.http.HttpRequestView;
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

        HttpRequestView request = mock(HttpRequestView.class);
        SessionContext ctx = inspector.inspect(request, "identityhash1");

        assertThat(ctx.present()).isTrue();
        assertThat(ctx.sessionIdHash()).isEqualTo("deadbeef");
        assertThat(ctx.newSession()).isTrue();
    }

    @Test
    void authenticationInspectorLambdaReturnsAuthenticationContext() {
        AuthenticationInspector inspector =
            (request, identityHash) -> AuthenticationContext.unauthenticated(false);

        HttpRequestView request = mock(HttpRequestView.class);
        AuthenticationContext auth = inspector.inspect(request, "identityhash1");

        assertThat(auth.authenticated()).isFalse();
        assertThat(auth.authenticationInfrastructurePresent()).isFalse();
    }
}
