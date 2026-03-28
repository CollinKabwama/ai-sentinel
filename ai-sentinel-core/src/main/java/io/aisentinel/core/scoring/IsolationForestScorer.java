package io.aisentinel.core.scoring;

import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.model.RequestFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Isolation Forest-based anomaly scorer. Uses a bounded training buffer and
 * async retrain to produce scores in [0,1]. When no model is loaded, returns
 * configurable fallback score so CompositeScorer effectively relies on StatisticalScorer.
 */
public final class IsolationForestScorer implements AnomalyScorer {

    private static final Logger log = LoggerFactory.getLogger(IsolationForestScorer.class);

    private final BoundedTrainingBuffer buffer;
    private final IsolationForestConfig config;
    private final IsolationForestTrainer trainer;

    private volatile IsolationForestModel model;
    private final AtomicLong modelVersion = new AtomicLong(0);
    private volatile long lastRetrainTimeMillis;
    private final AtomicLong retrainFailureCount = new AtomicLong(0);
    private volatile long lastRetrainFailureTimeMillis;
    private final AtomicLong acceptedTrainingSampleCount = new AtomicLong(0);
    private final AtomicLong rejectedTrainingSampleCount = new AtomicLong(0);
    private final SentinelMetrics metrics;

    public IsolationForestScorer(BoundedTrainingBuffer buffer, IsolationForestConfig config) {
        this(buffer, config, SentinelMetrics.NOOP);
    }

    public IsolationForestScorer(BoundedTrainingBuffer buffer, IsolationForestConfig config, SentinelMetrics metrics) {
        this.buffer = buffer;
        this.config = config;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
        this.trainer = new IsolationForestTrainer(
            config.getNumTrees(),
            config.getMaxDepth(),
            config.getRandomSeed()
        );
    }

    @Override
    public double score(RequestFeatures features) {
        return evaluateScore(features, true);
    }

    /**
     * @param recordRequestMetrics when true, records IF score histogram and inference latency (request path).
     */
    private double evaluateScore(RequestFeatures features, boolean recordRequestMetrics) {
        IsolationForestModel m = model;
        if (m == null) {
            double fb = config.getFallbackScore();
            if (recordRequestMetrics) {
                metrics.recordIsolationForestScore(fb);
            }
            return fb;
        }
        double[] x = features.toArray();
        long t0 = System.nanoTime();
        double s = m.score(x);
        long infNanos = System.nanoTime() - t0;
        if (recordRequestMetrics) {
            metrics.recordIsolationForestInferenceLatencyNanos(infNanos);
        }
        if (Double.isNaN(s) || s < 0) {
            double fb = config.getFallbackScore();
            if (recordRequestMetrics) {
                metrics.recordIsolationForestScore(fb);
            }
            return fb;
        }
        double out = Math.min(1.0, Math.max(0.0, s));
        if (recordRequestMetrics) {
            metrics.recordIsolationForestScore(out);
        }
        return out;
    }

    @Override
    public void update(RequestFeatures features) {
        if (config.getSampleRate() <= 0) return;
        IsolationForestModel m = model;
        if (m != null) {
            double anomalyScore = evaluateScore(features, false);
            double rejectionThreshold = config.getTrainingRejectionScoreThreshold();
            if (anomalyScore > rejectionThreshold) {
                rejectedTrainingSampleCount.incrementAndGet();
                return;
            }
        }
        if (config.getSampleRate() >= 1.0 || ThreadLocalRandomHolder.nextDouble() < config.getSampleRate()) {
            buffer.add(features.toArray());
            acceptedTrainingSampleCount.incrementAndGet();
        }
    }

    /**
     * Trains a new model from the buffer and atomically swaps it in.
     * Safe to call from a background scheduler. Training failures do not affect the current model.
     */
    public void retrain() {
        List<double[]> samples = buffer.getSnapshotForTraining();
        if (samples.size() < config.getMinTrainingSamples()) return;
        long startMs = System.currentTimeMillis();
        long t0 = System.nanoTime();
        try {
            IsolationForestModel newModel = trainer.train(samples);
            if (newModel != null) {
                model = newModel;
                long v = modelVersion.incrementAndGet();
                lastRetrainTimeMillis = System.currentTimeMillis();
                long durationMs = lastRetrainTimeMillis - startMs;
                log.info("IF retrain v{} completed in {}ms using {} samples", v, durationMs, samples.size());
                metrics.recordRetrainSuccessNanos(System.nanoTime() - t0);
            }
        } catch (Exception e) {
            retrainFailureCount.incrementAndGet();
            lastRetrainFailureTimeMillis = System.currentTimeMillis();
            log.warn("Isolation Forest retrain failed (request path unaffected): {}", e.getMessage());
            metrics.recordRetrainFailureNanos(System.nanoTime() - t0);
        }
    }

    public long getLastRetrainTimeMillis() { return lastRetrainTimeMillis; }
    public long getModelVersion() { return modelVersion.get(); }
    public int getBufferedSampleCount() { return buffer.size(); }
    public boolean isModelLoaded() { return model != null; }

    /**
     * Age of the current model in milliseconds, or -1 if no model is loaded.
     */
    public long getModelAgeMillis() {
        if (model == null) return -1L;
        long t = lastRetrainTimeMillis;
        return t <= 0 ? -1L : (System.currentTimeMillis() - t);
    }

    public long getRetrainFailureCount() { return retrainFailureCount.get(); }
    public long getLastRetrainFailureTimeMillis() { return lastRetrainFailureTimeMillis; }

    public long getAcceptedTrainingSampleCount() {
        return acceptedTrainingSampleCount.get();
    }

    public long getRejectedTrainingSampleCount() {
        return rejectedTrainingSampleCount.get();
    }

    /** ThreadLocalRandom for sampling without allocating per request. */
    private static final class ThreadLocalRandomHolder {
        private static final java.util.concurrent.ThreadLocalRandom get() {
            return java.util.concurrent.ThreadLocalRandom.current();
        }
        static double nextDouble() { return get().nextDouble(); }
    }
}
