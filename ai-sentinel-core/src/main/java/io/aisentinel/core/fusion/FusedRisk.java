package io.aisentinel.core.fusion;

/**
 * Phase 4 — anomaly + trust fused into a single risk input for {@link io.aisentinel.core.policy.PolicyEngine}.
 *
 * @param anomalyScore clamped anomaly score in {@code [0,1]} used as fusion input
 * @param trustScore     trust in {@code [0,1]} used as fusion input (higher = more trusted)
 * @param fusedScore     bounded fused value passed to policy
 * @param fusionDetail   human-readable breakdown for operators / hooks
 */
public record FusedRisk(double anomalyScore, double trustScore, double fusedScore, String fusionDetail) {}
