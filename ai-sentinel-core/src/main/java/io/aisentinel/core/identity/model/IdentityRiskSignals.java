package io.aisentinel.core.identity.model;

import java.util.Map;

/**
 * Named numeric components contributing to identity-side risk (used with behavioral trust evaluation).
 */
public record IdentityRiskSignals(Map<String, Double> components) {
    public IdentityRiskSignals {
        components = components != null ? Map.copyOf(components) : Map.of();
    }

    public static IdentityRiskSignals empty() {
        return new IdentityRiskSignals(Map.of());
    }
}
