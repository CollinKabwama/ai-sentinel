package io.aisentinel.autoconfigure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

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
}
