package io.aisentinel.core.policy;

import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Phase 3: optionally escalates {@link EnforcementAction} using identity trust and scope rules.
 * Anomaly {@link PolicyEngine} output is the baseline; this layer only increases severity (never relaxes).
 */
public interface TrustPolicyAdjuster {

    TrustPolicyAdjustment adjust(EnforcementAction baseline,
                                 double riskScore,
                                 RequestFeatures features,
                                 String endpoint,
                                 HttpServletRequest request,
                                 RequestContext ctx);
}
