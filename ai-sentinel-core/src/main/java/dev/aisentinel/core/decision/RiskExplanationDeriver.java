package dev.aisentinel.core.decision;

import dev.aisentinel.core.identity.IdentityContextKeys;
import dev.aisentinel.core.identity.IdentityRiskSignalKeys;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.scoring.StatisticalScoreSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic derivation of {@link RiskExplanation} from authoritative pipeline state.
 * <p>
 * Must run only after the enforcement action is finalized. Never invents factors outside
 * {@link RiskFactorCode}. Unknown signal keys are ignored (fail closed for invention).
 */
public final class RiskExplanationDeriver {

    private static final Set<String> KNOWN_TRUST_SIGNAL_KEYS = Set.of(
        IdentityRiskSignalKeys.SPARSE_HISTORY,
        IdentityRiskSignalKeys.NEW_SESSION,
        IdentityRiskSignalKeys.IP_DRIFT,
        IdentityRiskSignalKeys.USER_AGENT_DRIFT,
        IdentityRiskSignalKeys.REQUEST_BURST
    );

    private static final Set<String> VELOCITY_FEATURES = Set.of("requestsPerWindow");
    private static final Set<String> ENDPOINT_FEATURES = Set.of("endpointEntropy", "endpointConcentration");

    private static final Comparator<RiskFactor> FACTOR_ORDER = Comparator
        .comparingInt((RiskFactor f) -> severityRank(f.severity())).reversed()
        .thenComparing(Comparator.comparingDouble(RiskFactor::contribution).reversed())
        .thenComparing(f -> f.code().name());

    private RiskExplanationDeriver() {
    }

    /**
     * Derive explanation from finalized decision inputs. Safe with null evidence/context.
     */
    public static RiskExplanation derive(EnforcementAction action,
                                         Set<EvaluationStatus> statuses,
                                         DecisionExplanationEvidence evidence,
                                         RequestContext context,
                                         double anomalyScore,
                                         double policyScore) {
        Objects.requireNonNull(action, "action");
        EnumSet<EvaluationStatus> statusSet = EnumSet.noneOf(EvaluationStatus.class);
        if (statuses != null) {
            for (EvaluationStatus s : statuses) {
                if (s != null) {
                    statusSet.add(s);
                }
            }
        }

        EnumMap<RiskFactorCode, RiskFactor> byCode = new EnumMap<>(RiskFactorCode.class);

        boolean invalidScore = statusSet.contains(EvaluationStatus.INVALID_SCORE);
        boolean degraded = statusSet.contains(EvaluationStatus.DEGRADED);
        boolean warmup = statusSet.contains(EvaluationStatus.STATISTICAL_WARMUP);

        if (invalidScore) {
            put(byCode, new RiskFactor(
                RiskFactorCode.INVALID_SCORE_SIGNAL,
                RiskFactorCategory.MODEL,
                RiskFactorSeverity.HIGH,
                1.0,
                1.0,
                EvaluationStatus.INVALID_SCORE.name(),
                "Scorer returned a non-actionable invalid score; treated as pipeline quality, not proven attack.",
                "status"));
        }

        if (statusSet.contains(EvaluationStatus.MODEL_UNAVAILABLE)) {
            put(byCode, new RiskFactor(
                RiskFactorCode.MODEL_UNAVAILABLE,
                RiskFactorCategory.MODEL,
                RiskFactorSeverity.MEDIUM,
                0.7,
                0.9,
                EvaluationStatus.MODEL_UNAVAILABLE.name(),
                "Isolation Forest model was unavailable for this evaluation.",
                "status"));
        }

        if (statusSet.contains(EvaluationStatus.MODEL_FALLBACK_USED)) {
            put(byCode, new RiskFactor(
                RiskFactorCode.MODEL_FALLBACK,
                RiskFactorCategory.MODEL,
                RiskFactorSeverity.LOW,
                0.5,
                0.85,
                EvaluationStatus.MODEL_FALLBACK_USED.name(),
                "Isolation Forest used a fallback score mode.",
                "status"));
        }

        if (degraded) {
            put(byCode, new RiskFactor(
                RiskFactorCode.PIPELINE_DEGRADED,
                RiskFactorCategory.SYSTEM,
                RiskFactorSeverity.MEDIUM,
                0.6,
                0.9,
                EvaluationStatus.DEGRADED.name(),
                "Optional subsystem degraded; signal is pipeline health, not proven malice.",
                "status"));
        }

        if (warmup) {
            put(byCode, new RiskFactor(
                RiskFactorCode.STATISTICAL_WARMUP,
                RiskFactorCategory.SYSTEM,
                RiskFactorSeverity.INFO,
                0.2,
                0.5,
                EvaluationStatus.STATISTICAL_WARMUP.name(),
                "Statistical scorer is still warming up; numeric score is not confirmed elevated risk.",
                "status"));
        }

        if (statusSet.contains(EvaluationStatus.BASELINE_UPDATE_SKIPPED)) {
            put(byCode, new RiskFactor(
                RiskFactorCode.BASELINE_UPDATE_SKIPPED,
                RiskFactorCategory.SYSTEM,
                RiskFactorSeverity.INFO,
                0.25,
                0.8,
                EvaluationStatus.BASELINE_UPDATE_SKIPPED.name(),
                "Baseline update was skipped for this request under the configured update policy.",
                "status"));
        }

        // Behavioral factors only when score path is valid (not INVALID_SCORE).
        if (!invalidScore) {
            StatisticalScoreSnapshot snap = evidence != null ? evidence.statisticalExplanation() : null;
            if (snap != null && !snap.warmup() && snap.dominantFeature() != null) {
                addBehavioralFactor(byCode, snap, anomalyScore, policyScore);
            }
            addTrustAndIdentityFactors(byCode, context);
        }

        List<RiskFactor> ordered = normalizeAndOrder(byCode);
        SecurityAdvice advice = selectAdvice(action, statusSet, ordered, invalidScore, degraded);
        return new RiskExplanation(ordered, advice);
    }

    private static void addBehavioralFactor(EnumMap<RiskFactorCode, RiskFactor> byCode,
                                            StatisticalScoreSnapshot snap,
                                            double anomalyScore,
                                            double policyScore) {
        String feature = snap.dominantFeature();
        double cappedZ = snap.cappedAbsZ() != null && Double.isFinite(snap.cappedAbsZ())
            ? Math.max(0.0, snap.cappedAbsZ())
            : 0.0;
        double scoreHint = Double.isFinite(policyScore) ? policyScore
            : (Double.isFinite(anomalyScore) ? anomalyScore : 0.0);
        double contribution = clamp01(Math.max(scoreHint, Math.min(1.0, cappedZ / 6.0)));
        double confidence = 0.85;
        RiskFactorSeverity severity = severityFromContribution(contribution);

        RiskFactorCode code;
        RiskFactorCategory category = RiskFactorCategory.BEHAVIOR;
        String explanation;
        if (VELOCITY_FEATURES.contains(feature)) {
            code = RiskFactorCode.VELOCITY_ANOMALY;
            explanation = "Request velocity (requestsPerWindow) dominated the statistical anomaly signal.";
        } else if (ENDPOINT_FEATURES.contains(feature)) {
            code = RiskFactorCode.ENDPOINT_ACCESS_PATTERN;
            explanation = "Endpoint access pattern (" + feature + ") dominated the statistical anomaly signal.";
        } else {
            code = RiskFactorCode.BEHAVIOR_DEVIATION;
            explanation = "Behavioral feature '" + feature + "' dominated the statistical anomaly signal.";
        }

        // REQUEST_BURST trust signal reinforces velocity without inventing a new code.
        put(byCode, new RiskFactor(code, category, severity, contribution, confidence, feature, explanation, "statistical"));
    }

    private static void addTrustAndIdentityFactors(EnumMap<RiskFactorCode, RiskFactor> byCode, RequestContext context) {
        if (context == null) {
            return;
        }
        IdentityContext identity = context.get(IdentityContextKeys.IDENTITY_CONTEXT, IdentityContext.class);
        if (identity == null) {
            return;
        }

        double trust = identity.trust() != null ? identity.trust().value() : Double.NaN;
        if (Double.isFinite(trust) && trust < 0.55) {
            double contribution = clamp01(1.0 - trust);
            put(byCode, new RiskFactor(
                RiskFactorCode.TRUST_DEGRADATION,
                RiskFactorCategory.TRUST,
                severityFromContribution(contribution),
                contribution,
                0.8,
                "trustScore",
                "Identity trust score is degraded relative to the default baseline.",
                "trust"));
        }

        Map<String, Double> signals = identity.riskSignals() != null
            ? identity.riskSignals().components()
            : Map.of();
        for (Map.Entry<String, Double> e : signals.entrySet()) {
            String key = e.getKey();
            if (key == null || !KNOWN_TRUST_SIGNAL_KEYS.contains(key)) {
                continue; // unknown keys fail closed (ignored)
            }
            Double raw = e.getValue();
            if (raw == null || !Double.isFinite(raw) || raw <= 0.0) {
                continue;
            }
            double contribution = clamp01(raw);
            switch (key) {
                case IdentityRiskSignalKeys.NEW_SESSION -> put(byCode, new RiskFactor(
                    RiskFactorCode.IDENTITY_NEW_SESSION,
                    RiskFactorCategory.IDENTITY,
                    RiskFactorSeverity.LOW,
                    contribution,
                    0.7,
                    key,
                    "Identity presented a new session signal.",
                    "trust"));
                case IdentityRiskSignalKeys.SPARSE_HISTORY -> put(byCode, new RiskFactor(
                    RiskFactorCode.IDENTITY_SPARSE_HISTORY,
                    RiskFactorCategory.IDENTITY,
                    RiskFactorSeverity.LOW,
                    contribution,
                    0.75,
                    key,
                    "Identity has sparse behavioral history.",
                    "trust"));
                case IdentityRiskSignalKeys.IP_DRIFT -> put(byCode, new RiskFactor(
                    RiskFactorCode.NETWORK_IP_DRIFT,
                    RiskFactorCategory.NETWORK,
                    RiskFactorSeverity.MEDIUM,
                    contribution,
                    0.75,
                    key,
                    "Client IP drift relative to the identity baseline.",
                    "trust"));
                case IdentityRiskSignalKeys.USER_AGENT_DRIFT -> put(byCode, new RiskFactor(
                    RiskFactorCode.NETWORK_UA_DRIFT,
                    RiskFactorCategory.NETWORK,
                    RiskFactorSeverity.MEDIUM,
                    contribution,
                    0.75,
                    key,
                    "User-Agent drift relative to the identity baseline.",
                    "trust"));
                case IdentityRiskSignalKeys.REQUEST_BURST -> {
                    // Prefer velocity anomaly code when burst is present; merge into velocity if already there.
                    RiskFactor existing = byCode.get(RiskFactorCode.VELOCITY_ANOMALY);
                    if (existing != null) {
                        put(byCode, existing.withContribution(clamp01(Math.max(existing.contribution(), contribution))));
                    } else {
                        put(byCode, new RiskFactor(
                            RiskFactorCode.VELOCITY_ANOMALY,
                            RiskFactorCategory.BEHAVIOR,
                            severityFromContribution(contribution),
                            contribution,
                            0.7,
                            key,
                            "Identity trust evaluator reported a request-burst signal.",
                            "trust"));
                    }
                }
                default -> {
                    // unreachable for known set; ignore
                }
            }
        }
    }

    private static SecurityAdvice selectAdvice(EnforcementAction action,
                                               EnumSet<EvaluationStatus> statuses,
                                               List<RiskFactor> factors,
                                               boolean invalidScore,
                                               boolean degraded) {
        List<RiskFactorCode> linked = factors.stream().map(RiskFactor::code).toList();

        // Existing quarantine is operationally authoritative even when the score path is invalid.
        if (action == EnforcementAction.QUARANTINE) {
            return new SecurityAdvice(
                AdvisoryCode.RELEASE_QUARANTINE_AFTER_REVIEW,
                AdvisoryPriority.HIGH,
                "Identity is quarantined; release only after operator review.",
                linked,
                true);
        }

        if (invalidScore) {
            return new SecurityAdvice(
                AdvisoryCode.REVIEW_SCORER_HEALTH,
                AdvisoryPriority.HIGH,
                "Invalid scorer output; review scorer health. Not treated as proven attack.",
                linked.isEmpty() ? List.of(RiskFactorCode.INVALID_SCORE_SIGNAL) : linked,
                true);
        }

        if (statuses.contains(EvaluationStatus.BASELINE_RELEARNED)
            || statuses.contains(EvaluationStatus.BASELINE_UPDATE_SKIPPED)) {
            boolean hasBehavior = factors.stream().anyMatch(f -> f.category() == RiskFactorCategory.BEHAVIOR);
            if (hasBehavior || statuses.contains(EvaluationStatus.BASELINE_RELEARNED)) {
                return new SecurityAdvice(
                    AdvisoryCode.REVIEW_BASELINE,
                    AdvisoryPriority.MEDIUM,
                    "Baseline update was skipped or relearned; review baseline health if behavior looks unexpected.",
                    linked,
                    true);
            }
        }

        if (statuses.contains(EvaluationStatus.MODEL_UNAVAILABLE)
            || statuses.contains(EvaluationStatus.MODEL_FALLBACK_USED)) {
            return new SecurityAdvice(
                AdvisoryCode.REVIEW_SCORER_HEALTH,
                AdvisoryPriority.MEDIUM,
                "Model path degraded or unavailable; review Isolation Forest health.",
                linked,
                true);
        }

        if (degraded && factors.stream().noneMatch(f -> f.category() == RiskFactorCategory.BEHAVIOR)) {
            return new SecurityAdvice(
                AdvisoryCode.OTHER_OPERATOR_REVIEW,
                AdvisoryPriority.MEDIUM,
                "Pipeline reported DEGRADED without behavioral attack evidence.",
                linked,
                true);
        }

        boolean strongBehavior = factors.stream().anyMatch(f ->
            f.category() == RiskFactorCategory.BEHAVIOR && f.severity().ordinal() >= RiskFactorSeverity.MEDIUM.ordinal());
        boolean trustHit = factors.stream().anyMatch(f ->
            f.code() == RiskFactorCode.TRUST_DEGRADATION || f.category() == RiskFactorCategory.NETWORK);

        if (strongBehavior && trustHit) {
            return new SecurityAdvice(
                AdvisoryCode.REQUIRE_ADDITIONAL_VERIFICATION,
                AdvisoryPriority.HIGH,
                "Behavioral and trust/network factors co-occur; consider additional verification (advisory only).",
                linked,
                true);
        }

        if (strongBehavior
            || action == EnforcementAction.BLOCK
            || action == EnforcementAction.THROTTLE) {
            return new SecurityAdvice(
                AdvisoryCode.INVESTIGATE,
                AdvisoryPriority.MEDIUM,
                "Elevated behavioral or policy-band signal; investigate contributing factors.",
                linked,
                false);
        }

        if (factors.isEmpty()) {
            return new SecurityAdvice(
                AdvisoryCode.OBSERVE,
                AdvisoryPriority.LOW,
                "No material risk factors derived; continue observing.",
                List.of(),
                false);
        }

        return new SecurityAdvice(
            AdvisoryCode.OBSERVE,
            AdvisoryPriority.LOW,
            "Low-severity factors only; continue observing.",
            linked,
            false);
    }

    private static void put(EnumMap<RiskFactorCode, RiskFactor> byCode, RiskFactor factor) {
        RiskFactor existing = byCode.get(factor.code());
        if (existing == null) {
            byCode.put(factor.code(), factor);
            return;
        }
        // Dedup: keep higher severity, then higher contribution, then existing (stable).
        int cmp = Integer.compare(severityRank(factor.severity()), severityRank(existing.severity()));
        if (cmp > 0 || (cmp == 0 && factor.contribution() > existing.contribution())) {
            byCode.put(factor.code(), factor);
        }
    }

    private static List<RiskFactor> normalizeAndOrder(EnumMap<RiskFactorCode, RiskFactor> byCode) {
        if (byCode.isEmpty()) {
            return List.of();
        }
        List<RiskFactor> raw = new ArrayList<>(byCode.values());
        double sum = 0.0;
        for (RiskFactor f : raw) {
            sum += f.contribution();
        }
        List<RiskFactor> normalized = new ArrayList<>(raw.size());
        if (sum > 0.0 && Double.isFinite(sum)) {
            for (RiskFactor f : raw) {
                normalized.add(f.withContribution(clamp01(f.contribution() / sum)));
            }
        } else {
            double even = clamp01(1.0 / raw.size());
            for (RiskFactor f : raw) {
                normalized.add(f.withContribution(even));
            }
        }
        normalized.sort(FACTOR_ORDER);
        return List.copyOf(normalized);
    }

    private static int severityRank(RiskFactorSeverity severity) {
        return severity == null ? -1 : severity.ordinal();
    }

    private static RiskFactorSeverity severityFromContribution(double contribution) {
        if (contribution >= 0.75) {
            return RiskFactorSeverity.HIGH;
        }
        if (contribution >= 0.45) {
            return RiskFactorSeverity.MEDIUM;
        }
        if (contribution >= 0.2) {
            return RiskFactorSeverity.LOW;
        }
        return RiskFactorSeverity.INFO;
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v)) {
            return 0.0;
        }
        if (v < 0.0) {
            return 0.0;
        }
        if (v > 1.0) {
            return 1.0;
        }
        return v;
    }

}
