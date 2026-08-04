package dev.aisentinel.core.policy;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.http.HttpRequestView;

/**
 * Optionally escalates {@link EnforcementAction} using identity trust and scope rules.
 * Anomaly {@link PolicyEngine} output is the baseline; this layer only increases severity (never relaxes).
 */
public interface TrustPolicyAdjuster {

    TrustPolicyAdjustment adjust(EnforcementAction baseline,
                                 double riskScore,
                                 RequestFeatures features,
                                 String endpoint,
                                 HttpRequestView request,
                                 RequestContext ctx);
}
