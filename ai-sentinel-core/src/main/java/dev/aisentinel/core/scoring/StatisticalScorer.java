package dev.aisentinel.core.scoring;

import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.EvaluationStatusContributionContext;
import dev.aisentinel.core.decision.EvaluationStatusContributor;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.IdentityEndpointKey;
import dev.aisentinel.core.model.RequestFeatures;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Statistical anomaly scorer using Welford's online algorithm for rolling mean/std.
 * Score is z-score based, normalized to [0.0, 1.0].
 * State updates and reads are synchronized for happens-before; internal maps are bounded with TTL.
 * <p>
 * Idle keys older than the configured TTL are expired on score/update paths so statistical history
 * does not outlive the rolling request-window baseline lifetime.
 * <p>
 * Consumes {@link RequestFeatures#toStatisticalArray()} (behavioral dims only). Identity-like
 * hash/IP features are excluded; near-zero variance is mitigated with role-aware resolution floors
 * and per-feature {@code |z|} caps rather than raising the global numerical {@code MIN_STD}.
 */
public final class StatisticalScorer implements AnomalyScorer, EvaluationStatusContributor {

    /** Numerical floor only — prevents divide-by-zero; not the anti-FP mitigation. */
    private static final double MIN_STD = 1e-6;
    private static final double SIGMOID_SCALE = 3.0;
    /** Throttles the O(n) idle-expiry scan; bounds worst-case staleness of expiry to about this long past TTL. */
    private static final long EXPIRE_SWEEP_INTERVAL_MS = 1000L;

    /**
     * Per-dimension measurement resolution for {@link RequestFeatures#toStatisticalArray()} order.
     * Floors are role-evidence for each feature's natural quantization — not an arbitrary
     * {@code MIN_STD} hike. {@code requestsPerWindow} uses 2.0 (not the integer quantum 1.0):
     * a rolling-window fill under steady traffic is a +1 staircase, and with floor 1.0 the early
     * steps reach {@code z≈2} (THROTTLE) then freeze under default {@code ALLOW_OR_MONITOR}
     * gating, escalating benign identities to QUARANTINE. Floor 2.0 keeps unit-step scores in
     * ALLOW/MONITOR so learning continues through window fill / ramp asymptote, while genuine
     * volume shocks (large {@code Δ} requestsPerWindow) still saturate.
     */
    private static final double[] STD_RESOLUTION = {
        2.0,  // requestsPerWindow — see class note above
        0.05, // endpointEntropy (nats)
        0.05, // endpointConcentration (share)
        1.0,  // tokenAgeSeconds
        1.0,  // parameterCount
        1.0   // payloadSizeBytes
    };

    /**
     * Per-dimension {@code |z|} caps (same order). Rate stays nearly uncapped so genuine bursts
     * still saturate; ordinal/magnitude dims are capped so a constant-history unit flip cannot
     * alone force {@code max|z|} → score 1.0 / QUARANTINE.
     */
    private static final double[] Z_CAP = {
        20.0, // requestsPerWindow — high enough that sigmoid saturates to 1.0 in double
        6.0,  // endpointEntropy
        6.0,  // endpointConcentration
        6.0,  // tokenAgeSeconds
        2.0,  // parameterCount — unit/large flips alone cannot exceed THROTTLE band
        2.0   // payloadSizeBytes
    };

    static {
        if (STD_RESOLUTION.length != FeatureSchema.STATISTICAL_DIMENSION
            || Z_CAP.length != FeatureSchema.STATISTICAL_DIMENSION) {
            throw new ExceptionInInitializerError(
                "StatisticalScorer dimension tables must match FeatureSchema.STATISTICAL_DIMENSION");
        }
    }

    private final Map<IdentityEndpointKey, WelfordState> stateByKey = new ConcurrentHashMap<>();
    private final int maxKeys;
    private final long ttlMs;
    private final int warmupMinSamples;
    private final double warmupScore;
    private final SentinelMetrics metrics;
    private final AtomicLong nextExpireSweepMs = new AtomicLong(0);
    private final AtomicLong expireSweepCount = new AtomicLong(0);
    /** Most recent score explanation (volatile publish; diagnostic — last invocation globally). */
    private volatile StatisticalScoreSnapshot lastScoreSnapshot;
    /** Optional test seam invoked after this invocation's snapshot is published (same thread). */
    private volatile java.util.function.Consumer<RequestFeatures> afterScoreHookForTests;

    public StatisticalScorer() {
        this(100_000, 300_000L, 2, 0.4);
    }

    public StatisticalScorer(int maxKeys, long ttlMs) {
        this(maxKeys, ttlMs, 2, 0.4);
    }

    public StatisticalScorer(int maxKeys, long ttlMs, int warmupMinSamples, double warmupScore) {
        this(maxKeys, ttlMs, warmupMinSamples, warmupScore, SentinelMetrics.NOOP);
    }

    public StatisticalScorer(int maxKeys, long ttlMs, int warmupMinSamples, double warmupScore,
                             SentinelMetrics metrics) {
        this.maxKeys = Math.max(1, maxKeys);
        this.ttlMs = Math.max(1000L, ttlMs);
        this.warmupMinSamples = Math.max(0, warmupMinSamples);
        this.warmupScore = warmupScore < 0 ? 0 : Math.min(1.0, warmupScore);
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
    }

    /** Configured idle TTL in milliseconds (minimum 1000). */
    public long ttlMs() {
        return ttlMs;
    }

    /** Configured max retained identity|endpoint keys. */
    public int maxKeys() {
        return maxKeys;
    }

    /** Welford state map size (for cache gauges). */
    public int metricsStateEntryCount() {
        return stateByKey.size();
    }

    /** Number of idle-expiry sweeps actually performed (tests / ops). */
    public long expireSweepCount() {
        return expireSweepCount.get();
    }

    /** Configured idle-expiry sweep interval in milliseconds. */
    public long expireSweepIntervalMs() {
        return EXPIRE_SWEEP_INTERVAL_MS;
    }

    /**
     * Removes Welford state for an identity|endpoint key so the next observation re-enters warmup.
     *
     * @return {@code true} when a key was present and removed
     */
    public boolean reset(String identityHash, String endpoint) {
        return stateByKey.remove(IdentityEndpointKey.forEndpoint(identityHash, endpoint)) != null;
    }

    /** Removes all Welford state (tests / full restart simulation). */
    public void resetAll() {
        stateByKey.clear();
    }

    @Override
    public void contributeEvaluationStatuses(RequestFeatures features, EvaluationStatusContributionContext context) {
        if (features == null) {
            context.add(EvaluationStatus.DEGRADED);
            return;
        }
        if (isWarmup(features)) {
            context.add(EvaluationStatus.STATISTICAL_WARMUP);
        } else {
            context.add(EvaluationStatus.STATISTICAL_LIVE);
        }
    }

    /**
     * {@code true} when this identity|endpoint key lacks enough samples for live z-score scoring.
     * Does not mutate state. Used by the decision engine for {@link EvaluationStatus}
     * without expanding the pluggable {@link AnomalyScorer} SPI.
     */
    public boolean isWarmup(RequestFeatures features) {
        IdentityEndpointKey key = features.identityEndpointKey();
        expireIdle(features.effectiveTimestampMillis());
        return isWarmupWithoutExpire(key);
    }

    /** Configured numeric score returned while {@link #isWarmup(RequestFeatures)} is true (telemetry / fusion input). */
    public double warmupScore() {
        return warmupScore;
    }

    /**
     * Explanation from the most recent {@link #score(RequestFeatures)} call on this instance, or {@code null}
     * if {@link #score} has never run.
     * <p>
     * <strong>Diagnostic only:</strong> this is the last scorer invocation globally on this JVM instance —
     * not request-scoped. Pipeline / actuator decision explanation must not depend on this getter.
     */
    public StatisticalScoreSnapshot getLastScoreSnapshot() {
        return lastScoreSnapshot;
    }

    /** Test-only seam for deterministic concurrency interleaving. */
    public void setAfterScoreHookForTests(java.util.function.Consumer<RequestFeatures> hook) {
        this.afterScoreHookForTests = hook;
    }

    /**
     * Scores and returns an immutable explanation for <em>this</em> invocation (request-safe).
     * Also publishes {@link #getLastScoreSnapshot()} for diagnostics.
     */
    public StatisticalScoreOutcome scoreWithExplanation(RequestFeatures features) {
        StatisticalScoreOutcome outcome = scoreInternal(features);
        lastScoreSnapshot = outcome.snapshot();
        java.util.function.Consumer<RequestFeatures> hook = afterScoreHookForTests;
        if (hook != null) {
            hook.accept(features);
        }
        return outcome;
    }

    @Override
    public double score(RequestFeatures features) {
        return scoreWithExplanation(features).score();
    }

    private StatisticalScoreOutcome scoreInternal(RequestFeatures features) {
        double[] x = features.toStatisticalArray();
        IdentityEndpointKey key = features.identityEndpointKey();
        long now = features.effectiveTimestampMillis();
        expireIdle(now);

        if (isWarmupWithoutExpire(key)) {
            metrics.recordStatisticalScore(warmupScore);
            return new StatisticalScoreOutcome(warmupScore, StatisticalScoreSnapshot.warmup(warmupScore));
        }
        WelfordState state = stateByKey.get(key);
        if (state == null) {
            metrics.recordStatisticalScore(warmupScore);
            return new StatisticalScoreOutcome(warmupScore, StatisticalScoreSnapshot.warmup(warmupScore));
        }
        double[] means;
        double[] stds;
        synchronized (state) {
            state.touch(now);
            means = state.getMeansCopy();
            stds = state.getStds();
        }
        int dim = Math.min(x.length, means.length);
        double maxCappedZ = 0;
        int dominantIdx = -1;
        double dominantObserved = 0;
        double dominantMean = 0;
        double dominantEffStd = 0;
        double dominantRawAbsZ = 0;
        double dominantCappedAbsZ = 0;
        for (int i = 0; i < dim; i++) {
            double mean = means[i];
            double resolution = i < STD_RESOLUTION.length ? STD_RESOLUTION[i] : MIN_STD;
            double std = Math.max(stds[i], Math.max(MIN_STD, resolution));
            double rawAbsZ = Math.abs((x[i] - mean) / std);
            if (Double.isNaN(rawAbsZ) || Double.isInfinite(rawAbsZ)) {
                rawAbsZ = 2.0;
            }
            double cap = i < Z_CAP.length ? Z_CAP[i] : 6.0;
            double capped = Math.min(rawAbsZ, cap);
            if (capped >= maxCappedZ) {
                maxCappedZ = capped;
                dominantIdx = i;
                dominantObserved = x[i];
                dominantMean = mean;
                dominantEffStd = std;
                dominantRawAbsZ = rawAbsZ;
                dominantCappedAbsZ = capped;
            }
        }
        double s = sigmoid(maxCappedZ);
        // Non-finite sigmoid output is an invalid score for the decision engine (not maximum risk).
        if (Double.isNaN(s) || Double.isInfinite(s)) {
            metrics.recordStatisticalScore(Double.NaN);
            StatisticalScoreSnapshot invalidSnap = new StatisticalScoreSnapshot(
                Double.NaN,
                false,
                StatisticalFeatureNames.nameAt(dominantIdx),
                dominantObserved,
                dominantMean,
                dominantEffStd,
                dominantRawAbsZ,
                dominantCappedAbsZ
            );
            return new StatisticalScoreOutcome(Double.NaN, invalidSnap);
        }
        double out = Math.min(1.0, Math.max(0.0, s));
        metrics.recordStatisticalScore(out);
        StatisticalScoreSnapshot snap = new StatisticalScoreSnapshot(
            out,
            false,
            StatisticalFeatureNames.nameAt(dominantIdx),
            dominantObserved,
            dominantMean,
            dominantEffStd,
            dominantRawAbsZ,
            dominantCappedAbsZ
        );
        return new StatisticalScoreOutcome(out, snap);
    }

    /** Immutable score + explanation from one {@link #scoreWithExplanation} invocation. */
    public record StatisticalScoreOutcome(double score, StatisticalScoreSnapshot snapshot) {
    }

    @Override
    public void update(RequestFeatures features) {
        double[] x = features.toStatisticalArray();
        IdentityEndpointKey key = features.identityEndpointKey();
        long now = features.effectiveTimestampMillis();
        expireIdle(now);

        stateByKey.compute(key, (k, s) -> {
            WelfordState st = s != null ? s : new WelfordState(x.length);
            st.update(x, now);
            return st;
        });

        evictIfOverCapacity(now);
    }

    /**
     * Drops keys whose last access is older than TTL, throttled to at most one full-map scan per
     * {@link #EXPIRE_SWEEP_INTERVAL_MS} regardless of request volume. An unconditional per-call scan
     * is O(n) in tracked-key count on every score/update; at realistic production cardinality this
     * measurably regressed request latency, so the sweep itself is rate-limited rather than the
     * expiry guarantee (idle keys are still guaranteed to expire, within one sweep interval of TTL).
     */
    void expireIdle(long now) {
        long next = nextExpireSweepMs.get();
        if (now < next) {
            return;
        }
        if (!nextExpireSweepMs.compareAndSet(next, now + EXPIRE_SWEEP_INTERVAL_MS)) {
            return;
        }
        expireSweepCount.incrementAndGet();
        long cutoff = now - ttlMs;
        stateByKey.entrySet().removeIf(e -> e.getValue().lastAccessMs() < cutoff);
    }

    private boolean isWarmupWithoutExpire(IdentityEndpointKey key) {
        WelfordState state = stateByKey.get(key);
        if (state == null) {
            return true;
        }
        synchronized (state) {
            return state.n < Math.max(2, warmupMinSamples);
        }
    }

    private void evictIfOverCapacity(long now) {
        if (stateByKey.size() <= maxKeys) {
            return;
        }
        long cutoff = now - ttlMs;
        stateByKey.entrySet().removeIf(e -> e.getValue().lastAccessMs() < cutoff);
        while (stateByKey.size() > maxKeys) {
            IdentityEndpointKey victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<IdentityEndpointKey, WelfordState> e : stateByKey.entrySet()) {
                long la = e.getValue().lastAccessMs();
                if (la < oldest) {
                    oldest = la;
                    victim = e.getKey();
                }
            }
            if (victim == null) {
                break;
            }
            stateByKey.remove(victim);
        }
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-SIGMOID_SCALE * (z - 2.0)));
    }

    private static final class WelfordState {
        private final double[] means;
        private final double[] m2;
        private int n;
        private volatile long lastAccessMs;

        WelfordState(int dim) {
            this.means = new double[dim];
            this.m2 = new double[dim];
        }

        /** Cap n to avoid overflow (n-1 used in getStds); keeps variance defined. */
        private static final int MAX_N = Integer.MAX_VALUE - 1;

        synchronized void update(double[] x, long nowMs) {
            if (n < MAX_N) n++;
            for (int i = 0; i < x.length; i++) {
                double delta = x[i] - means[i];
                means[i] += delta / n;
                double delta2 = x[i] - means[i];
                m2[i] += delta * delta2;
            }
            lastAccessMs = nowMs;
        }

        synchronized void touch(long nowMs) {
            lastAccessMs = nowMs;
        }

        synchronized double[] getMeansCopy() {
            double[] c = new double[means.length];
            System.arraycopy(means, 0, c, 0, means.length);
            return c;
        }

        synchronized double[] getStds() {
            double[] stds = new double[means.length];
            for (int i = 0; i < means.length; i++) {
                stds[i] = n > 1 ? Math.sqrt(m2[i] / (n - 1)) : 0;
            }
            return stds;
        }

        long lastAccessMs() {
            return lastAccessMs;
        }
    }
}
