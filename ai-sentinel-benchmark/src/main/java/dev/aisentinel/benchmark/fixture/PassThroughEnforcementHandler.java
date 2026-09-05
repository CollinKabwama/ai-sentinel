package dev.aisentinel.benchmark.fixture;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.policy.EnforcementAction;

/**
 * Enforcement double that never quarantines and always continues the chain.
 * Used so benchmarks measure decision cost rather than denial handling.
 */
public enum PassThroughEnforcementHandler implements EnforcementHandler {
    INSTANCE;

    @Override
    public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                         String identityHash, String endpoint) {
        return true;
    }

    @Override
    public boolean isQuarantined(String identityHash, String endpoint) {
        return false;
    }
}
