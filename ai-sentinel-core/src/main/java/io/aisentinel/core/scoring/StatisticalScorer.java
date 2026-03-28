package io.aisentinel.core.scoring;

import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.model.RequestFeatures;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Statistical anomaly scorer using Welford's online algorithm for rolling mean/std.
 * Score is z-score based, normalized to [0.0, 1.0].
 * State updates and reads are synchronized for happens-before; internal maps are bounded with TTL.
 */
public final class StatisticalScorer implements AnomalyScorer {

    private static final double MIN_STD = 1e-6;
    private static final double SIGMOID_SCALE = 3.0;

    private final Map<String, WelfordState> stateByKey = new ConcurrentHashMap<>();
    private final int maxKeys;
    private final long ttlMs;
    private final int warmupMinSamples;
    private final double warmupScore;
    private final SentinelMetrics metrics;

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

    /** Welford state map size (for cache gauges). */
    public int metricsStateEntryCount() {
        return stateByKey.size();
    }

    @Override
    public double score(RequestFeatures features) {
        double[] x = features.toArray();
        String key = features.identityHash() + "|" + features.endpoint();

        WelfordState state = stateByKey.get(key);
        if (state == null) {
            metrics.recordStatisticalScore(warmupScore);
            return warmupScore;
        }
        double[] means;
        double[] stds;
        int n;
        synchronized (state) {
            n = state.n;
            if (n < Math.max(2, warmupMinSamples)) {
                metrics.recordStatisticalScore(warmupScore);
                return warmupScore;
            }
            means = state.getMeansCopy();
            stds = state.getStds();
        }
        double maxZ = 0;
        for (int i = 0; i < x.length; i++) {
            double mean = means[i];
            double std = Math.max(stds[i], MIN_STD);
            double z = Math.abs((x[i] - mean) / std);
            if (Double.isNaN(z) || Double.isInfinite(z)) z = 2.0;
            maxZ = Math.max(maxZ, z);
        }
        double s = sigmoid(maxZ);
        double out = Double.isNaN(s) ? 1.0 : Math.min(1.0, Math.max(0.0, s));
        metrics.recordStatisticalScore(out);
        return out;
    }

    @Override
    public void update(RequestFeatures features) {
        double[] x = features.toArray();
        String key = features.identityHash() + "|" + features.endpoint();
        long now = System.currentTimeMillis();

        stateByKey.compute(key, (k, s) -> {
            WelfordState st = s != null ? s : new WelfordState(x.length);
            st.update(x, now);
            return st;
        });

        evictIfNeeded(now);
    }

    private void evictIfNeeded(long now) {
        if (stateByKey.size() <= maxKeys) return;
        long cutoff = now - ttlMs;
        stateByKey.entrySet().removeIf(e -> e.getValue().lastAccessMs() < cutoff);
        while (stateByKey.size() > maxKeys) {
            String victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, WelfordState> e : stateByKey.entrySet()) {
                long la = e.getValue().lastAccessMs();
                if (la < oldest) {
                    oldest = la;
                    victim = e.getKey();
                }
            }
            if (victim == null) break;
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
