package io.aisentinel.core.identity.trust;

import io.aisentinel.core.identity.IdentityRiskSignalKeys;
import io.aisentinel.core.identity.model.IdentityContext;
import io.aisentinel.core.identity.model.IdentityRiskSignals;
import io.aisentinel.core.identity.model.TrustEvaluation;
import io.aisentinel.core.identity.model.TrustScore;
import io.aisentinel.core.identity.spi.TrustEvaluator;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lightweight behavioral trust from per-identity baselines. Observational only; does not alter API policy by itself.
 */
public final class BehavioralIdentityTrustEvaluator implements TrustEvaluator {

    private final BehavioralBaselineStore baselineStore;
    private final double burstRequestsThreshold;
    private final double sparseHistoryTrustCap;
    private final double maxTotalPenalty;

    public BehavioralIdentityTrustEvaluator(BehavioralBaselineStore baselineStore,
                                           double burstRequestsThreshold,
                                           double sparseHistoryTrustCap,
                                           double maxTotalPenalty) {
        this.baselineStore = baselineStore;
        this.burstRequestsThreshold = burstRequestsThreshold > 0 ? burstRequestsThreshold : 25.0;
        this.sparseHistoryTrustCap = sparseHistoryTrustCap > 0 && sparseHistoryTrustCap <= 1.0 ? sparseHistoryTrustCap : 0.82;
        this.maxTotalPenalty = maxTotalPenalty > 0 && maxTotalPenalty <= 1.0 ? maxTotalPenalty : 0.75;
    }

    @Override
    public TrustEvaluation evaluate(IdentityContext identity, HttpServletRequest request, RequestFeatures features,
                                    RequestContext ctx) {
        String key = baselineKey(identity, features.identityHash());
        long now = features.timestampMillis() > 0 ? features.timestampMillis() : System.currentTimeMillis();

        BehavioralBaselineEntry before = baselineStore.updateAndGetPrevious(
            key,
            features.endpoint(),
            features.headerFingerprintHash(),
            features.ipBucket(),
            now
        );

        Map<String, Double> signals = new LinkedHashMap<>();

        boolean sparse = before == null || before.observationCount < 2;
        if (sparse) {
            signals.put(IdentityRiskSignalKeys.SPARSE_HISTORY, 0.35);
        }

        if (identity.session().present() && identity.session().newSession()) {
            signals.put(IdentityRiskSignalKeys.NEW_SESSION, 0.18);
        }

        if (before != null && before.observationCount >= 1) {
            if (before.lastIpBucket != Integer.MIN_VALUE && before.lastIpBucket != features.ipBucket()) {
                signals.put(IdentityRiskSignalKeys.IP_DRIFT, 0.2);
            }
            if (before.lastHeaderFingerprintHash != 0L
                && before.lastHeaderFingerprintHash != features.headerFingerprintHash()) {
                signals.put(IdentityRiskSignalKeys.USER_AGENT_DRIFT, 0.2);
            }
        }

        double rpw = features.requestsPerWindow();
        if (rpw > burstRequestsThreshold) {
            double excess = (rpw - burstRequestsThreshold) / Math.max(burstRequestsThreshold, 1.0);
            signals.put(IdentityRiskSignalKeys.REQUEST_BURST, Math.min(1.0, 0.15 + 0.35 * excess));
        }

        double rawPenalty = signals.values().stream().mapToDouble(Double::doubleValue).sum();
        double penalty = Math.min(maxTotalPenalty, rawPenalty);

        // Floor keeps trust observational: never imply total distrust or lockout semantics; allows recovery on subsequent requests.
        double trust = Math.max(0.12, 1.0 - penalty);
        if (sparse) {
            trust = Math.min(trust, sparseHistoryTrustCap);
        }
        trust = Math.min(1.0, Math.max(0.0, trust));

        String reason = signals.isEmpty() ? "behavioral-stable" : "behavioral-v1(" + signals.size() + "-signals)";
        return new TrustEvaluation(new TrustScore(trust, reason), new IdentityRiskSignals(signals));
    }

    static String baselineKey(IdentityContext identity, String identityHash) {
        if (identity.authentication().authenticated() && !identity.authentication().principalName().isEmpty()) {
            return "p:" + identity.authentication().principalName();
        }
        if (identity.session().present() && !identity.session().sessionIdHash().isEmpty()) {
            return "s:" + identity.session().sessionIdHash();
        }
        return "i:" + (identityHash != null ? identityHash : "");
    }
}
