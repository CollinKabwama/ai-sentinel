package io.aisentinel.autoconfigure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for proxy-aware client IP resolution (A5).
 * Uses static resolveClientIp to avoid mocking final SentinelPipeline.
 */
class SentinelFilterProxyTest {

    @Test
    void whenTrustedProxyAndXForwardedFor_usesRightmostUntrustedClient() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.1.100, 10.0.0.1");

        String ip = SentinelFilter.resolveClientIp(request, List.of("127.0.0.1", "10.0.0.1"));

        assertThat(ip).isEqualTo("192.168.1.100");
    }

    @Test
    void whenNotTrustedProxy_ignoresXForwardedFor() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.1.100");

        String ip = SentinelFilter.resolveClientIp(request, List.of("10.0.0.1"));

        assertThat(ip).isEqualTo("127.0.0.1");
    }

    @Test
    void whenTrustedProxiesEmpty_usesRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1");

        String ip = SentinelFilter.resolveClientIp(request, List.of());

        assertThat(ip).isEqualTo("192.168.1.1");
    }

    @Test
    void whenTrustedProxy_usesForwardedHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("Forwarded")).thenReturn("for=\"203.0.113.195\";proto=http");

        String ip = SentinelFilter.resolveClientIp(request, List.of("127.0.0.1"));

        assertThat(ip).isEqualTo("203.0.113.195");
    }

    @Test
    void cidrTrustedProxyMatchesRemote() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.50");
        when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.1.100, 10.0.0.1");

        String ip = SentinelFilter.resolveClientIp(request, List.of("10.0.0.0/24"));

        assertThat(ip).isEqualTo("192.168.1.100");
    }

    @Test
    void xffSpoofingIgnoredWhenRemoteNotTrusted() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.2");
        when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 10.0.0.1");

        String ip = SentinelFilter.resolveClientIp(request, List.of("127.0.0.1"));

        assertThat(ip).isEqualTo("198.51.100.2");
    }

    @Test
    void whenTrustedProxy_usesXRealIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("Forwarded")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.2");

        String ip = SentinelFilter.resolveClientIp(request, List.of("127.0.0.1"));

        assertThat(ip).isEqualTo("198.51.100.2");
    }
}
