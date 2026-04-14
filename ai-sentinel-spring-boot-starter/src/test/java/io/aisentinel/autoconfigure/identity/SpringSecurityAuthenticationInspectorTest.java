package io.aisentinel.autoconfigure.identity;

import io.aisentinel.core.identity.model.AuthenticationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
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
        HttpServletRequest request = mock(HttpServletRequest.class);
        assertThat(inspector.inspect(request, "hash"))
            .isEqualTo(AuthenticationContext.unauthenticated());
    }

    @Test
    void returnsPrincipalWhenAuthenticated() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("alice", "cred", List.of()));
        HttpServletRequest request = mock(HttpServletRequest.class);
        assertThat(inspector.inspect(request, "hash"))
            .isEqualTo(AuthenticationContext.ofPrincipal("alice"));
    }
}
