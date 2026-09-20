package dev.aisentinel.core.contract;

import dev.aisentinel.core.http.MapHttpRequestView;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationContractMapperPrivacyTest {

    @Test
    void javaMapperForwardsOnlySafeHeaderSubset() {
        MapHttpRequestView view = new MapHttpRequestView()
            .requestUri("/api/orders")
            .method("POST")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Content-Length", "256")
            .header("User-Agent", "safe-agent")
            .header("X-Token-Issued-At", "1700000000")
            .header("X-Request-ID", "req-1")
            .header("X-Correlation-ID", "corr-1")
            .header("Authorization", "Bearer secret-token")
            .header("Cookie", "session=secret")
            .header("Set-Cookie", "server-secret")
            .header("Proxy-Authorization", "Basic secret")
            .header("X-API-Key", "key-secret")
            .header("X-Custom-Secret", "custom-secret")
            .parameter("password", "secret-password")
            .parameter("token", "secret-token");

        EvaluationRequest request = EvaluationContractMapper.fromHttpRequestView(view, "hash-1", "corr-1");

        assertThat(request.headers())
            .containsEntry("accept", "application/json")
            .containsEntry("content-type", "application/json")
            .containsEntry("content-length", "256")
            .containsEntry("user-agent", "safe-agent")
            .containsEntry("x-token-issued-at", "1700000000")
            .containsEntry("x-request-id", "req-1")
            .containsEntry("x-correlation-id", "corr-1")
            .containsEntry("authorization", EvaluationContractMapper.AUTHORIZATION_PRESENT_SENTINEL)
            .doesNotContainKeys(
                "cookie",
                "set-cookie",
                "proxy-authorization",
                "x-api-key",
                "x-custom-secret"
            );
        assertThat(request.parameters())
            .containsExactly(
                Map.entry("p0", EvaluationContractMapper.PARAMETER_PRESENT_SENTINEL),
                Map.entry("p1", EvaluationContractMapper.PARAMETER_PRESENT_SENTINEL)
            )
            .doesNotContainKeys("password", "token");
        assertThat(request.parameters().values())
            .doesNotContain("secret-password", "secret-token");
    }

    @Test
    void authorizationFilteringIsCaseInsensitiveAndPresenceOnly() {
        MapHttpRequestView view = new MapHttpRequestView()
            .requestUri("/api/orders")
            .method("GET")
            .header("aUtHoRiZaTiOn", "Bearer secret-token")
            .header("CONTENT-LENGTH", "64")
            .header("X-TOKEN-ISSUED-AT", "1700000000");

        EvaluationRequest request = EvaluationContractMapper.fromHttpRequestView(view, "hash-1", "corr-1");

        assertThat(request.headers())
            .containsEntry("authorization", EvaluationContractMapper.AUTHORIZATION_PRESENT_SENTINEL)
            .containsEntry("content-length", "64")
            .containsEntry("x-token-issued-at", "1700000000");
        assertThat(request.headers().values())
            .doesNotContain("Bearer secret-token");
    }

    @Test
    void mapperStripsQueryAndFragmentFromPathBeforeRemoteTransport() {
        MapHttpRequestView view = new MapHttpRequestView()
            .requestUri("/api/orders?token=secret#frag")
            .method("GET");

        EvaluationRequest request = EvaluationContractMapper.fromHttpRequestView(view, "hash-1", "corr-1");

        assertThat(request.path()).isEqualTo("/api/orders");
    }
}
