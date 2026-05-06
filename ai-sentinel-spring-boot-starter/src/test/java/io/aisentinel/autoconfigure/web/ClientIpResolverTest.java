package io.aisentinel.autoconfigure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientIpResolverTest {

    @Test
    void xffRightmostUntrustedSkipsTrustedProxies() {
        String ip = ClientIpResolver.resolveFromXForwardedFor(
            "192.168.1.100, 10.0.0.1",
            List.of("127.0.0.1", "10.0.0.1"));
        assertThat(ip).isEqualTo("192.168.1.100");
    }

    @Test
    void cidrTrustMatches() {
        assertThat(ClientIpResolver.isTrustedProxy("10.0.0.50", List.of("10.0.0.0/24"))).isTrue();
        assertThat(ClientIpResolver.isTrustedProxy("10.0.1.1", List.of("10.0.0.0/24"))).isFalse();
    }

    @Test
    void forwardedHeaderParsesMultipleSegmentsRightmostUntrusted() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeaders("Forwarded")).thenReturn(Collections.enumeration(List.of(
            "for=192.168.1.1;proto=http, for=192.0.2.60;proto=https")));
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        String ip = ClientIpResolver.resolveClientIp(request, List.of("127.0.0.1", "192.0.2.60"));

        assertThat(ip).isEqualTo("192.168.1.1");
    }

    @Test
    void untrustedRemoteIgnoresForgedXRealIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.2");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("Forwarded")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn("1.1.1.1");

        String ip = ClientIpResolver.resolveClientIp(request, List.of("127.0.0.1"));

        assertThat(ip).isEqualTo("198.51.100.2");
    }

    @Test
    void trustedProxyUsesXRealIpOnlyWhenNoForwardChainHint() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("Forwarded")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.2");

        String ip = ClientIpResolver.resolveClientIp(request, List.of("127.0.0.1"));

        assertThat(ip).isEqualTo("198.51.100.2");
    }

    @Test
    void trustedProxyWithPlaceholderXffIgnoresXRealIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(",,");
        when(request.getHeader("Forwarded")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn("9.9.9.9");

        String ip = ClientIpResolver.resolveClientIp(request, List.of("127.0.0.1"));

        assertThat(ip).isEqualTo("127.0.0.1");
    }

    @Test
    void trustedProxyXffWinsOverXRealIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.10, 10.0.0.1");
        when(request.getHeader("X-Real-IP")).thenReturn("9.9.9.9");

        String ip = ClientIpResolver.resolveClientIp(request, List.of("127.0.0.1", "10.0.0.1"));

        assertThat(ip).isEqualTo("203.0.113.10");
    }

    @Test
    void noForwardedHeadersFallsBackToRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.50");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("Forwarded")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);

        String ip = ClientIpResolver.resolveClientIp(request, List.of("10.0.0.0/24"));

        assertThat(ip).isEqualTo("10.0.0.50");
    }

    @Test
    void hasForwardedChainHint_blankXffIsFalse() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(" ");
        assertThat(ClientIpResolver.hasForwardedChainHint(request)).isFalse();
    }

    @Test
    void hasForwardedChainHint_nonBlankXffIsTrue() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(",");
        assertThat(ClientIpResolver.hasForwardedChainHint(request)).isTrue();
    }

    @Test
    void isTrustedProxy_invalidIpv4PrefixLength_returnsFalse() {
        assertThat(ClientIpResolver.isTrustedProxy("10.0.0.1", List.of("10.0.0.0/33"))).isFalse();
    }

    @Test
    void isTrustedProxy_nonNumericPrefix_returnsFalse() {
        assertThat(ClientIpResolver.isTrustedProxy("10.0.0.1", List.of("10.0.0.0/xx"))).isFalse();
    }

    @Test
    void isTrustedProxy_ipv4ClientAgainstIpv6Cidr_returnsFalse() {
        assertThat(ClientIpResolver.isTrustedProxy("10.0.0.1", List.of("2001:db8::/32"))).isFalse();
    }

    @Test
    void isTrustedProxy_skipsNullAndBlankPatterns() {
        List<String> patterns = new ArrayList<>();
        patterns.add(null);
        patterns.add("  ");
        patterns.add("10.0.0.0/24");
        assertThat(ClientIpResolver.isTrustedProxy("10.0.0.50", patterns)).isTrue();
    }

    @Test
    void isTrustedProxy_malformedCidrSkippedWhenLaterPatternMatches() {
        assertThat(ClientIpResolver.isTrustedProxy("10.0.0.50", List.of("10.0.0.0/xx", "10.0.0.0/24"))).isTrue();
    }

    @Test
    void isTrustedProxy_slashOnlyPattern_returnsFalse() {
        assertThat(ClientIpResolver.isTrustedProxy("127.0.0.1", List.of("/"))).isFalse();
    }

    @Test
    void isTrustedProxy_emptyClientIp_returnsFalse() {
        assertThat(ClientIpResolver.isTrustedProxy("", List.of("127.0.0.1"))).isFalse();
    }
}
