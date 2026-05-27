package io.aisentinel.autoconfigure.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringSecurityPrincipalBridgeTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsPrincipalNameWhenAuthenticated() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            "alice", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(SpringSecurityPrincipalBridge.resolveAuthenticatedPrincipalName()).isEqualTo("alice");
    }

    @Test
    void returnsNullWhenAnonymousPrincipal() {
        AnonymousAuthenticationToken auth = new AnonymousAuthenticationToken(
            "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(SpringSecurityPrincipalBridge.resolveAuthenticatedPrincipalName()).isNull();
    }

    @Test
    void returnsNullWhenNotAuthenticated() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("bob", "n/a");
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(SpringSecurityPrincipalBridge.resolveAuthenticatedPrincipalName()).isNull();
    }

    @Test
    void returnsNullWhenNoAuthentication() {
        SecurityContextHolder.clearContext();

        assertThat(SpringSecurityPrincipalBridge.resolveAuthenticatedPrincipalName()).isNull();
    }
}
