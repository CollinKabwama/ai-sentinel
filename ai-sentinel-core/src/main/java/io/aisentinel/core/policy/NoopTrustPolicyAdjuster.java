package io.aisentinel.core.policy;

import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;

public enum NoopTrustPolicyAdjuster implements TrustPolicyAdjuster {
    INSTANCE;

    @Override
    public TrustPolicyAdjustment adjust(EnforcementAction baseline,
                                        double riskScore,
                                        RequestFeatures features,
                                        String endpoint,
                                        HttpServletRequest request,
                                        RequestContext ctx) {
        return new TrustPolicyAdjustment(baseline, "");
    }
}
