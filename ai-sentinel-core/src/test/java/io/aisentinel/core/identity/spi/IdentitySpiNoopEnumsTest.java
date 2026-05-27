package io.aisentinel.core.identity.spi;

import io.aisentinel.core.identity.IdentityContextKeys;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * Covers core {@code io.aisentinel.core.identity.spi} no-op adapters referenced when identity is disabled.
 */
class IdentitySpiNoopEnumsTest {

    @Test
    void noopIdentityContextResolverLeavesContextUnchanged() {
        IdentityContextResolver resolver = NoopIdentityContextResolver.INSTANCE;
        assertThat(resolver).isInstanceOf(IdentityContextResolver.class);

        RequestContext ctx = new RequestContext();
        IdentityContext before = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.none(),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty());
        ctx.put(IdentityContextKeys.IDENTITY_CONTEXT, before);

        HttpServletRequest request = mock(HttpServletRequest.class);
        resolver.resolve(request, "identityhash1", ctx);

        assertThat(ctx.get(IdentityContextKeys.IDENTITY_CONTEXT, IdentityContext.class)).isSameAs(before);
    }

    @Test
    void noopTrustEvaluatorReturnsNull() {
        TrustEvaluator evaluator = NoopTrustEvaluator.INSTANCE;
        assertThat(evaluator).isInstanceOf(TrustEvaluator.class);

        IdentityContext id = new IdentityContext(
            AuthenticationContext.unauthenticated(),
            SessionContext.none(),
            TrustScore.fullyTrusted(),
            IdentityRiskSignals.empty());
        HttpServletRequest request = mock(HttpServletRequest.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("ih")
            .endpoint("/")
            .timestampMillis(1L)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(0)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0L)
            .ipBucket(0)
            .build();
        RequestContext ctx = new RequestContext();

        TrustEvaluation out = evaluator.evaluate(id, request, features, ctx);

        assertThat(out).isNull();
    }

    @Test
    void noopIdentityResponseHookCompletes() {
        IdentityResponseHook hook = NoopIdentityResponseHook.INSTANCE;
        assertThat(hook).isInstanceOf(IdentityResponseHook.class);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("ih")
            .endpoint("/")
            .timestampMillis(1L)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(0)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0L)
            .ipBucket(0)
            .build();
        RequestContext ctx = new RequestContext();

        assertThatCode(() -> hook.afterPipeline(request, response, "identityhash1", features, ctx, true))
            .doesNotThrowAnyException();
    }
}
