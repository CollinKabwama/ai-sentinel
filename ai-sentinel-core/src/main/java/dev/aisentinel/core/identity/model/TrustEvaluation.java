package dev.aisentinel.core.identity.model;

/**
 * Result of {@link dev.aisentinel.core.identity.spi.TrustEvaluator#evaluate} when trust is computed.
 */
public record TrustEvaluation(TrustScore trustScore, IdentityRiskSignals riskSignals) {
    public TrustEvaluation {
        if (trustScore == null) {
            throw new IllegalArgumentException("trustScore");
        }
        riskSignals = riskSignals != null ? riskSignals : IdentityRiskSignals.empty();
    }
}
