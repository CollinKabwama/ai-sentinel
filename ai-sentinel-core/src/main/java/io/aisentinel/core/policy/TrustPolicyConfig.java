package io.aisentinel.core.policy;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of trust-aware policy settings (Phase 3).
 */
public record TrustPolicyConfig(
    boolean enabled,
    boolean authenticatedOnly,
    List<String> protectedEndpointPatterns,
    Set<String> httpMethodsUpper,
    double trustNoEffectMinimum,
    double trustMediumBandMinimum,
    double trustLowBandMinimum,
    boolean denyOnCriticalTrustEnabled,
    boolean requireMinRiskForTrustDeny,
    double minRiskScoreForTrustDeny
) {
    public TrustPolicyConfig {
        protectedEndpointPatterns = List.copyOf(protectedEndpointPatterns != null ? protectedEndpointPatterns : List.of());
        httpMethodsUpper = httpMethodsUpper != null && !httpMethodsUpper.isEmpty()
            ? httpMethodsUpper.stream().map(s -> s.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet())
            : Set.of();
        validateThresholds(trustLowBandMinimum, trustMediumBandMinimum, trustNoEffectMinimum);
    }

    public static TrustPolicyConfig disabled() {
        return new TrustPolicyConfig(
            false,
            true,
            List.of(),
            Set.of(),
            0.80,
            0.50,
            0.25,
            false,
            true,
            0.40);
    }

    static void validateThresholds(double low, double medium, double noEffect) {
        if (Double.isNaN(low) || Double.isNaN(medium) || Double.isNaN(noEffect)) {
            throw new IllegalArgumentException("trust policy thresholds must be finite");
        }
        if (low < 0 || noEffect > 1.0 || !(low < medium && medium < noEffect)) {
            throw new IllegalArgumentException(
                "trust policy thresholds must satisfy trustLowBandMinimum < trustMediumBandMinimum < trustNoEffectMinimum; got "
                    + low + ", " + medium + ", " + noEffect);
        }
    }
}
