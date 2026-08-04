package dev.aisentinel.core.policy;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.http.HttpRequestView;

public enum NoopTrustPolicyAdjuster implements TrustPolicyAdjuster {
    INSTANCE;

    @Override
    public TrustPolicyAdjustment adjust(EnforcementAction baseline,
                                        double riskScore,
                                        RequestFeatures features,
                                        String endpoint,
                                        HttpRequestView request,
                                        RequestContext ctx) {
        return new TrustPolicyAdjustment(baseline, "");
    }
}
