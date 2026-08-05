package dev.aisentinel.autoconfigure.identity;

import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.http.HttpRequestView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SpringSecurityAuthenticationInspectorTest {

    private final SpringSecurityAuthenticationInspector inspector = new SpringSecurityAuthenticationInspector();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsUnauthenticatedWhenNoSecurityContext() {
        SecurityContextHolder.clearContext();
        HttpRequestView request = mock(HttpRequestView.class);
        assertThat(inspector.inspect(request, "hash"))
            .isEqualTo(AuthenticationContext.unauthenticated());
    }

    @Test
    void returnsPrincipalWhenAuthenticated() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", "cred", List.of()));
        HttpRequestView request = mock(HttpRequestView.class);
        assertThat(inspector.inspect(request, "hash"))
            .isEqualTo(AuthenticationContext.ofAuthenticated("alice", "UsernamePasswordAuthenticationToken", List.of()));
    }

    @Test
    void returnsRoleNamesWhenAuthoritiesPresent() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("bob", "c", List.of(new SimpleGrantedAuthority("ROLE_APP"))));
        HttpRequestView request = mock(HttpRequestView.class);
        assertThat(inspector.inspect(request, "hash").roleNames()).containsExactly("ROLE_APP");
    }
}
