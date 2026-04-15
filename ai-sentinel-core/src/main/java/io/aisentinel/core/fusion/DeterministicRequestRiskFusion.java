package io.aisentinel.core.fusion;

import java.util.Locale;

/**
 * Bounded deterministic fusion: low trust amplifies anomaly; high trust slightly dampens; small additive bump from
 * distrust so very low trust still elevates low anomaly scores. Does not replace the raw anomaly scorer.
 */
public final class DeterministicRequestRiskFusion implements RequestRiskFusion {

    private final double strength;

    public DeterministicRequestRiskFusion(double strength) {
        if (Double.isNaN(strength) || Double.isInfinite(strength)) {
            this.strength = 0.0;
        } else {
            this.strength = Math.max(0.0, Math.min(1.0, strength));
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public FusedRisk fuse(double anomalyScoreClamped, double trustScoreClamped) {
        double a = clamp01(anomalyScoreClamped);
        double t = clamp01(trustScoreClamped);
        double k = strength;
        double distrust = 1.0 - t;
        double mult = 1.0 + k * distrust;
        double dampen = 1.0 - 0.2 * k * t;
        double additive = 0.15 * k * distrust;
        double raw = a * mult * dampen + additive;
        double fused = Math.min(1.0, raw);
        String detail = String.format(Locale.ROOT,
            "fusion:anomaly=%.4f;trust=%.4f;strength=%.4f;distrust=%.4f;mult=%.4f;dampen=%.4f;additive=%.4f;fused=%.4f",
            a, t, k, distrust, mult, dampen, additive, fused);
        return new FusedRisk(a, t, fused, detail);
    }

    /**
     * Defensive clamp for inputs to {@link #fuse}; NaN maps to 0 so fusion stays bounded.
     * On the request path, anomaly and trust are already finite: {@link io.aisentinel.core.SentinelPipeline} clamps the
     * scorer first, and trust comes from {@link io.aisentinel.core.identity.model.TrustScore}.
     */
    private static double clamp01(double v) {
        if (Double.isNaN(v) || v < 0) {
            return 0.0;
        }
        return Math.min(1.0, v);
    }
}
