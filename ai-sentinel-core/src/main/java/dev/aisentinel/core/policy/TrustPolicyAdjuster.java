package dev.aisentinel.core.policy;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.model.IdentityContext;

/**
 * Optionally escalates {@link EnforcementAction} using identity trust and scope rules.
 * Anomaly {@link PolicyEngine} output is the baseline; this layer only increases severity (never relaxes).
 * <p>
 * Invoked on the request path inside {@link dev.aisentinel.core.decision.SentinelDecisionEngine}. Implementations
 * must be side-effect free aside from returning an adjustment result.
 */
public interface TrustPolicyAdjuster {

    /**
     * @param baseline   action chosen by {@link PolicyEngine}
     * @param riskScore  anomaly score used for trust-aware gates (clamped)
     * @param features   request features
     * @param endpoint   normalized endpoint key
     * @param request    current request view
     * @param ctx        per-request context (may hold {@link IdentityContext} and fusion detail)
     * @return adjusted action plus optional diagnostic detail for telemetry/context
     */
    TrustPolicyAdjustment adjust(EnforcementAction baseline,
                                 double riskScore,
                                 RequestFeatures features,
                                 String endpoint,
                                 HttpRequestView request,
                                 RequestContext ctx);
}
