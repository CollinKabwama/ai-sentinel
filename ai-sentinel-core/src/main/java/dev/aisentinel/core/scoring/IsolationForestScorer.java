package dev.aisentinel.core.scoring;

import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.model.ModelArtifactMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Isolation Forest-based anomaly scorer. Uses a bounded training buffer and
 * async retrain to produce scores in [0,1]. When no model is loaded (or the model
 * returns an invalid score), returns a configurable fallback score for visibility
 * and {@link LastScoreMode} telemetry. {@link CompositeScorer} includes IF in the
 * weighted blend only when {@link LastScoreMode#MODEL} — fallback values do not dilute
 * the statistical composite. Isolation Forest exposes a single scalar; it does not
 * provide per-feature attribution.
 */
public final class IsolationForestScorer implements AnomalyScorer {

    /** Which path last activated the in-memory IF model (observability; registry and local retrain are mutually exclusive in production). */
    public enum ActiveModelSource {
        NONE,
        LOCAL_RETRAIN,
        REGISTRY
    }

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

    /** Last successfully installed registry artifact id (empty if none or after a local retrain). */
    private volatile String registryArtifactVersion = "";
    private volatile long lastRegistryInstallTimeMillis;
    private final AtomicLong registryInstallFailureCount = new AtomicLong(0);
    private volatile ActiveModelSource activeModelSource = ActiveModelSource.NONE;
    private volatile LastScoreMode lastScoreMode = LastScoreMode.FALLBACK_NO_MODEL;

    /** How the most recent request-path {@link #score(RequestFeatures)} resolved. */
    public enum LastScoreMode {
        MODEL,
        FALLBACK_NO_MODEL,
        FALLBACK_INVALID
    }

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
        return scoreWithMode(features, true).score();
    }

    /** Result mode of the last {@link #score(RequestFeatures)} call on this instance (diagnostic — last invocation globally). */
    public LastScoreMode lastScoreMode() {
        return lastScoreMode;
    }

    /**
     * Scores and returns the mode for <em>this</em> invocation (request-safe).
     * Also publishes {@link #lastScoreMode()} for diagnostics.
     */
    public IsolationForestScoreOutcome scoreWithMode(RequestFeatures features) {
        return scoreWithMode(features, true);
    }

    /**
     * @param recordRequestMetrics when true, records IF score histogram and inference latency (request path).
     */
    private IsolationForestScoreOutcome scoreWithMode(RequestFeatures features, boolean recordRequestMetrics) {
        IsolationForestModel m = model;
        if (m == null) {
            LastScoreMode mode = LastScoreMode.FALLBACK_NO_MODEL;
            lastScoreMode = mode;
            double fb = config.getFallbackScore();
            if (recordRequestMetrics) {
                metrics.recordIsolationForestScore(fb);
                metrics.recordIsolationForestScoreMode(mode.name());
            }
            return new IsolationForestScoreOutcome(fb, mode);
        }
        double[] x = features.toIsolationForestArray();
        long t0 = System.nanoTime();
        double s = m.score(x);
        long infNanos = System.nanoTime() - t0;
        if (recordRequestMetrics) {
            metrics.recordIsolationForestInferenceLatencyNanos(infNanos);
        }
        if (Double.isNaN(s) || s < 0) {
            LastScoreMode mode = LastScoreMode.FALLBACK_INVALID;
            lastScoreMode = mode;
            double fb = config.getFallbackScore();
            if (recordRequestMetrics) {
                metrics.recordIsolationForestScore(fb);
                metrics.recordIsolationForestScoreMode(mode.name());
            }
            return new IsolationForestScoreOutcome(fb, mode);
        }
        LastScoreMode mode = LastScoreMode.MODEL;
        lastScoreMode = mode;
        double out = Math.min(1.0, Math.max(0.0, s));
        if (recordRequestMetrics) {
            metrics.recordIsolationForestScore(out);
            metrics.recordIsolationForestScoreMode(mode.name());
        }
        return new IsolationForestScoreOutcome(out, mode);
    }

    /** Immutable score + mode from one scoring invocation. */
    public record IsolationForestScoreOutcome(double score, LastScoreMode mode) {
    }

    @Override
    public void update(RequestFeatures features) {
        if (config.getSampleRate() <= 0) return;
        IsolationForestModel m = model;
        if (m != null) {
            double anomalyScore = scoreWithMode(features, false).score();
            double rejectionThreshold = config.getTrainingRejectionScoreThreshold();
            if (anomalyScore > rejectionThreshold) {
                rejectedTrainingSampleCount.incrementAndGet();
                return;
            }
        }
        if (config.getSampleRate() >= 1.0 || ThreadLocalRandomHolder.nextDouble() < config.getSampleRate()) {
            buffer.add(features.toIsolationForestArray());
            acceptedTrainingSampleCount.incrementAndGet();
        }
    }

    /**
     * Loads a registry-published artifact after checksum and dimension checks.
     * On any failure the current {@link #model} is unchanged (fail-open for inference).
     *
     * @return true if a new model was activated
     */
    public boolean tryInstallFromRegistry(ModelArtifactMetadata meta, byte[] payload) {
        if (meta == null || payload == null) {
            return false;
        }
        if (payload.length > IsolationForestModelCodec.MAX_PAYLOAD_BYTES) {
            registryInstallFailureCount.incrementAndGet();
            metrics.recordModelRegistryInstallFailure();
            return false;
        }
        if (!meta.isValidIsolationForestV1Pointer() || !meta.payloadMatches(payload)) {
            registryInstallFailureCount.incrementAndGet();
            metrics.recordModelRegistryInstallFailure();
            return false;
        }
        try {
            IsolationForestModel decoded = IsolationForestModelCodec.decode(payload);
            if (decoded.featureDimension() != meta.featureDimension()) {
                registryInstallFailureCount.incrementAndGet();
                metrics.recordModelRegistryInstallFailure();
                return false;
            }
            model = decoded;
            modelVersion.incrementAndGet();
            lastRetrainTimeMillis = meta.trainedAtEpochMillis();
            registryArtifactVersion = meta.modelVersion();
            activeModelSource = ActiveModelSource.REGISTRY;
            lastRegistryInstallTimeMillis = System.currentTimeMillis();
            metrics.recordModelRegistryInstallSuccess();
            log.info("Installed IF model from registry version={} (artifact schema {})",
                meta.modelVersion(), meta.artifactSchemaVersion());
            return true;
        } catch (Exception e) {
            registryInstallFailureCount.incrementAndGet();
            metrics.recordModelRegistryInstallFailure();
            log.warn("Registry model decode/install failed (keeping prior model): {}", e.toString());
            return false;
        }
    }

    public String getRegistryArtifactVersion() {
        return registryArtifactVersion;
    }

    public long getLastRegistryInstallTimeMillis() {
        return lastRegistryInstallTimeMillis;
    }

    public long getRegistryInstallFailureCount() {
        return registryInstallFailureCount.get();
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
                registryArtifactVersion = "";
                activeModelSource = ActiveModelSource.LOCAL_RETRAIN;
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

    public ActiveModelSource getActiveModelSource() {
        return activeModelSource;
    }

    /** ThreadLocalRandom for sampling without allocating per request. */
    private static final class ThreadLocalRandomHolder {
        private static final java.util.concurrent.ThreadLocalRandom get() {
            return java.util.concurrent.ThreadLocalRandom.current();
        }
        static double nextDouble() { return get().nextDouble(); }
    }
}
