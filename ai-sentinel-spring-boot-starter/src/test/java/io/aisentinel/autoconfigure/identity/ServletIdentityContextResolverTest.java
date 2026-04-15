package io.aisentinel.autoconfigure.identity;

import io.aisentinel.core.identity.IdentityContextKeys;
import io.aisentinel.core.identity.model.AuthenticationContext;
import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.SessionContext;
import io.aisentinel.core.identity.spi.AuthenticationInspector;
import io.aisentinel.core.identity.spi.SessionInspector;
import io.aisentinel.core.model.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServletIdentityContextResolverTest {

    @Mock
    private HttpServletRequest request;

    @Test
    void normalizedUnauthenticated_visitorStoredWhenInspectorsSaySo() {
        AuthenticationInspector auth = (r, h) -> AuthenticationContext.unauthenticated();
        SessionInspector sess = (r, h) -> SessionContext.none();
        var resolver = new ServletIdentityContextResolver(auth, sess);
        RequestContext ctx = new RequestContext();
        resolver.resolve(request, "hash", ctx);
        IdentityContext id = ctx.get(IdentityContextKeys.IDENTITY_CONTEXT, IdentityContext.class);
        assertThat(id).isNotNull();
        assertThat(id.authentication().authenticated()).isFalse();
        assertThat(id.authentication().anonymous()).isTrue();
        assertThat(id.authentication().authenticationInfrastructurePresent()).isTrue();
        assertThat(id.session().present()).isFalse();
    }

    @Test
    void normalizedAuthenticated_composedFromInspectors() {
        AuthenticationInspector auth = (r, h) ->
            AuthenticationContext.ofAuthenticated("alice", "JwtAuthenticationToken", List.of("ROLE_USER"));
        SessionInspector sess = (r, h) -> SessionContext.none();
        var resolver = new ServletIdentityContextResolver(auth, sess);
        RequestContext ctx = new RequestContext();
        resolver.resolve(request, "h", ctx);
        IdentityContext id = ctx.get(IdentityContextKeys.IDENTITY_CONTEXT, IdentityContext.class);
        assertThat(id.authentication().principalName()).isEqualTo("alice");
        assertThat(id.authentication().authenticationType()).isEqualTo("JwtAuthenticationToken");
        assertThat(id.authentication().roleNames()).containsExactly("ROLE_USER");
    }

    @Test
    void sessionPresent_hashedIdAndNewFlag() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        when(session.getId()).thenReturn("abc");
        when(session.isNew()).thenReturn(true);
        SessionInspector sess = new HttpSessionSessionInspector();
        SessionContext sc = sess.inspect(req, "h");
        assertThat(sc.present()).isTrue();
        assertThat(sc.newSession()).isTrue();
        assertThat(sc.sessionIdHash()).isNotEmpty();
    }

    @Test
    void partialData_authAnonymousWithSession_ok() {
        AuthenticationInspector auth = (r, h) -> AuthenticationContext.unauthenticated();
        SessionInspector sess = (r, h) -> SessionContext.ofHashedId("deadbeef", false);
        var resolver = new ServletIdentityContextResolver(auth, sess);
        RequestContext ctx = new RequestContext();
        resolver.resolve(request, "h", ctx);
        IdentityContext id = ctx.get(IdentityContextKeys.IDENTITY_CONTEXT, IdentityContext.class);
        assertThat(id.authentication().authenticated()).isFalse();
        assertThat(id.session().present()).isTrue();
        assertThat(id.session().sessionIdHash()).isEqualTo("deadbeef");
    }

    @Test
    void endToEndSpringSecurityAndSession() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("sam", "p", List.of(new SimpleGrantedAuthority("ROLE_X"))));
        try {
            AuthenticationInspector authInspector = new SpringSecurityAuthenticationInspector();
            HttpServletRequest req = mock(HttpServletRequest.class);
            HttpSession session = mock(HttpSession.class);
            when(req.getSession(false)).thenReturn(session);
            when(session.getId()).thenReturn("sid");
            when(session.isNew()).thenReturn(false);

            SessionInspector sessionInspector = new HttpSessionSessionInspector();
            var resolver = new ServletIdentityContextResolver(authInspector, sessionInspector);
            RequestContext ctx = new RequestContext();
            resolver.resolve(req, "h", ctx);
            IdentityContext id = ctx.get(IdentityContextKeys.IDENTITY_CONTEXT, IdentityContext.class);
            assertThat(id.authentication().principalName()).isEqualTo("sam");
            assertThat(id.authentication().authenticated()).isTrue();
            assertThat(id.authentication().roleNames()).contains("ROLE_X");
            assertThat(id.session().present()).isTrue();
            assertThat(id.session().newSession()).isFalse();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
