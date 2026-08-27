package dev.aisentinel.core.contract;

import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.spi.IdentityContextResolver;
import dev.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;

import java.util.Objects;

/**
 * Local Java bridge: {@link EvaluationRequest} → existing decision engine → {@link EvaluationResponse}.
 * <p>
 * Does not duplicate scoring/policy. Does not apply HTTP enforcement writes.
 * Callers that need side-effecting enforcement continue to use {@link dev.aisentinel.core.SentinelPipeline}.
 */
public final class LocalEvaluationBridge {

    private final FeatureExtractor featureExtractor;
    private final SentinelDecisionEngine decisionEngine;
    private final IdentityContextResolver identityContextResolver;

    public LocalEvaluationBridge(FeatureExtractor featureExtractor,
                                 SentinelDecisionEngine decisionEngine) {
        this(featureExtractor, decisionEngine, NoopIdentityContextResolver.INSTANCE);
    }

    public LocalEvaluationBridge(FeatureExtractor featureExtractor,
                                 SentinelDecisionEngine decisionEngine,
                                 IdentityContextResolver identityContextResolver) {
        this.featureExtractor = Objects.requireNonNull(featureExtractor, "featureExtractor");
        this.decisionEngine = Objects.requireNonNull(decisionEngine, "decisionEngine");
        this.identityContextResolver = identityContextResolver != null
            ? identityContextResolver
            : NoopIdentityContextResolver.INSTANCE;
    }

    /**
     * Evaluate via the authoritative local engine. Returns {@code null} decision mapping is not used —
     * when the engine returns {@code null} (fail-open hard path), this method returns {@code null}.
     */
    public EvaluationResponse evaluate(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        HttpRequestView view = EvaluationContractMapper.toHttpRequestView(request);
        RequestContext ctx = new RequestContext();
        try {
            identityContextResolver.resolve(view, request.identityKey(), ctx);
        } catch (RuntimeException ignored) {
            // Match pipeline fail-open for identity resolution: continue without identity context.
        }
        RequestFeatures features;
        try {
            features = featureExtractor.extract(view, request.identityKey(), ctx);
        } catch (RuntimeException e) {
            return null;
        }
        RiskDecision decision = decisionEngine.evaluate(view, request.identityKey(), features, ctx);
        if (decision == null) {
            return null;
        }
        boolean proceed = switch (decision.action()) {
            case ALLOW, MONITOR -> true;
            case THROTTLE, BLOCK, QUARANTINE -> false;
        };
        return EvaluationContractMapper.toResponse(request, decision, proceed);
    }
}
