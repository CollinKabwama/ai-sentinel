package dev.aisentinel.core.identity.spi;

import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.IdentityRiskSignals;
import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.identity.model.TrustEvaluation;
import dev.aisentinel.core.identity.model.TrustScore;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Contract tests for identity SPI no-op implementations referenced from {@link dev.aisentinel.core.SentinelPipeline}.
 */
class IdentitySpiNoopsTest {

    @Test
    void noopIdentityContextResolverLeavesContextUnchanged() {
        RequestContext ctx = new RequestContext();
        ctx.put("k", "v");
        NoopIdentityContextResolver.INSTANCE.resolve(mock(HttpRequestView.class), "hash", ctx);
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
            mock(HttpRequestView.class),
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
            mock(HttpRequestView.class),
            mock(EnforcementResponse.class),
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
        SessionContext sc = inspector.inspect(mock(HttpRequestView.class), "hash");
        assertThat(sc.sessionIdHash()).isEqualTo("abc");
        assertThat(sc.newSession()).isTrue();
    }

    @Test
    void authenticationInspectorFunctionalContract() {
        AuthenticationInspector auth = (req, hash) -> AuthenticationContext.ofPrincipal("user");
        assertThat(auth.inspect(mock(HttpRequestView.class), "h").principalName()).isEqualTo("user");
    }
}
