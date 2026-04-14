package io.aisentinel.core.identity.model;

import java.util.Map;

/**
 * Placeholder for future identity-side risk factors (fed into the shared threat engine in later phases).
 */
public record IdentityRiskSignals(Map<String, Double> components) {
    public IdentityRiskSignals {
        components = components != null ? Map.copyOf(components) : Map.of();
    }

    public static IdentityRiskSignals empty() {
        return new IdentityRiskSignals(Map.of());
    }
}
