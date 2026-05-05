package io.aisentinel.core.identity.spi;

import io.aisentinel.core.identity.model.AuthenticationContext;
import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.IdentityRiskSignals;
import io.aisentinel.core.identity.model.SessionContext;
import io.aisentinel.core.identity.model.TrustEvaluation;
import io.aisentinel.core.identity.model.TrustScore;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Contract tests for identity SPI no-op implementations referenced from {@link io.aisentinel.core.SentinelPipeline}.
 */
class IdentitySpiNoopsTest {

    @Test
    void noopIdentityContextResolverLeavesContextUnchanged() {
        RequestContext ctx = new RequestContext();
        ctx.put("k", "v");
        NoopIdentityContextResolver.INSTANCE.resolve(mock(HttpServletRequest.class), "hash", ctx);
        assertThat(ctx.get("k", String.class)).isEqualTo("v");
    }

    @Test
    void noopTrustEvaluatorReturnsNull() {
        IdentityContext id = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.none(),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty()
        );
        TrustEvaluation out = NoopTrustEvaluator.INSTANCE.evaluate(
            id,
            mock(HttpServletRequest.class),
            RequestFeatures.builder()
                .identityHash("h")
                .endpoint("/")
                .timestampMillis(0)
                .requestsPerWindow(1)
                .endpointEntropy(0)
                .tokenAgeSeconds(0)
                .parameterCount(0)
                .payloadSizeBytes(0)
                .headerFingerprintHash(0)
                .ipBucket(0)
                .build(),
            new RequestContext()
        );
        assertThat(out).isNull();
    }

    @Test
    void noopIdentityResponseHookAcceptsInvocation() {
        NoopIdentityResponseHook.INSTANCE.afterPipeline(
            mock(HttpServletRequest.class),
            mock(HttpServletResponse.class),
            "h",
            RequestFeatures.builder()
                .identityHash("h")
                .endpoint("/")
                .timestampMillis(0)
                .requestsPerWindow(1)
                .endpointEntropy(0)
                .tokenAgeSeconds(0)
                .parameterCount(0)
                .payloadSizeBytes(0)
                .headerFingerprintHash(0)
                .ipBucket(0)
                .build(),
            new RequestContext(),
            true
        );
    }

    @Test
    void sessionInspectorFunctionalContract() {
        SessionInspector inspector = (req, hash) -> SessionContext.ofHashedId("abc", true);
        SessionContext sc = inspector.inspect(mock(HttpServletRequest.class), "hash");
        assertThat(sc.sessionIdHash()).isEqualTo("abc");
        assertThat(sc.newSession()).isTrue();
    }

    @Test
    void authenticationInspectorFunctionalContract() {
        AuthenticationInspector auth = (req, hash) -> AuthenticationContext.ofPrincipal("user");
        assertThat(auth.inspect(mock(HttpServletRequest.class), "h").principalName()).isEqualTo("user");
    }
}
