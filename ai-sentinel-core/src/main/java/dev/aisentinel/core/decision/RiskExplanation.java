package dev.aisentinel.core.decision;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Structured explanation companion for {@link RiskDecision}: contributing factors plus optional advice.
 * Empty factors and null advice are valid and must not affect enforcement.
 *
 * @param factors immutable ordered factors (deterministic order)
 * @param advice  optional advisory recommendation; {@code null} means absent
 */
public record RiskExplanation(
    List<RiskFactor> factors,
    SecurityAdvice advice
) {
    private static final Comparator<RiskFactor> FACTOR_ORDER = Comparator
        .comparingInt((RiskFactor f) -> severityRank(f.severity())).reversed()
        .thenComparing(Comparator.comparingDouble(RiskFactor::contribution).reversed())
        .thenComparing(f -> f.code().name());

    public static RiskExplanation empty() {
        return new RiskExplanation(List.of(), null);
    }

    public RiskExplanation {
        factors = normalizeFactors(factors);
        if (advice != null && !advice.linkedFactorCodes().isEmpty()) {
            EnumSet<RiskFactorCode> present = EnumSet.noneOf(RiskFactorCode.class);
            for (RiskFactor factor : factors) {
                present.add(factor.code());
            }
            for (RiskFactorCode linked : advice.linkedFactorCodes()) {
                if (!present.contains(linked)) {
                    throw new IllegalArgumentException("advice linkedFactorCodes must reference present factors");
                }
            }
        }
    }

    public boolean isEmpty() {
        return factors.isEmpty() && advice == null;
    }

    public RiskFactor topFactor() {
        return factors.isEmpty() ? null : factors.get(0);
    }

    private static List<RiskFactor> normalizeFactors(List<RiskFactor> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        EnumSet<RiskFactorCode> seen = EnumSet.noneOf(RiskFactorCode.class);
        List<RiskFactor> copy = new ArrayList<>(input.size());
        for (RiskFactor factor : input) {
            Objects.requireNonNull(factor, "factor");
            if (!seen.add(factor.code())) {
                throw new IllegalArgumentException("duplicate risk factor code: " + factor.code());
            }
            copy.add(factor);
        }
        copy.sort(FACTOR_ORDER);
        return List.copyOf(copy);
    }

    private static int severityRank(RiskFactorSeverity severity) {
        return severity == null ? -1 : severity.ordinal();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RiskExplanation that)) {
            return false;
        }
        return Objects.equals(factors, that.factors) && Objects.equals(advice, that.advice);
    }

    @Override
    public int hashCode() {
        return Objects.hash(factors, advice);
    }
}
