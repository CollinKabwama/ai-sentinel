package io.aisentinel.autoconfigure.distributed.training;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.enforcement.EnforcementKeys;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.distributed.training.TrainingCandidatePublishRequest;
import io.aisentinel.distributed.training.TrainingCandidatePublisher;
import io.aisentinel.distributed.training.TrainingCandidateRecord;
import io.aisentinel.distributed.training.TrainingFingerprintHashes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Bounded async publisher: request thread only runs cheap gates, feature snapshots, hashes, and {@link Semaphore#tryAcquire()}.
 */
@Slf4j
public final class AsyncTrainingCandidatePublisher implements TrainingCandidatePublisher, DisposableBean {

    private static final int MIN_IN_FLIGHT = 1;
    private static final int MAX_IN_FLIGHT_CAP = 50_000;
    private static final int IF_FEATURE_LEN = 5;
    private static final int STAT_FEATURE_LEN = 7;

    private final SentinelProperties properties;
    private final SentinelMetrics metrics;
    private final TrainingPublishStatus status;
    private final TrainingCandidateTransport transport;
    private final ExecutorService executor;
    private final Semaphore inFlight;
    private final double ifAntiPoisonThreshold;

    public AsyncTrainingCandidatePublisher(SentinelProperties properties,
                                           SentinelMetrics metrics,
                                           TrainingPublishStatus status,
                                           TrainingCandidateTransport transport) {
        this.properties = properties;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
        this.status = status != null ? status : new TrainingPublishStatus();
        this.transport = transport;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        int configured = properties.getDistributed().getTrainingPublishMaxInFlight();
        int cap = Math.max(MIN_IN_FLIGHT, Math.min(configured, MAX_IN_FLIGHT_CAP));
        this.inFlight = new Semaphore(cap);
        double rej = properties.getIsolationForest().getTrainingRejectionScoreThreshold();
        if (rej < 0 || Double.isNaN(rej)) {
            rej = 0.7;
        }
        this.ifAntiPoisonThreshold = Math.min(1.0, rej);
    }

    @Override
    public void publish(TrainingCandidatePublishRequest request) {
        var d = properties.getDistributed();
        if (!d.isTrainingPublishEnabled()) {
            return;
        }
        double rate = d.getTrainingPublishSampleRate();
        if (rate <= 0) {
            return;
        }
        double minScore = d.getTrainingPublishMinCompositeScore();
        if (request.compositeScore() < minScore) {
            metrics.recordTrainingCandidatePublishSkippedGate();
            return;
        }
        if (d.isTrainingPublishApplyIfAntiPoisoning()) {
            Double ifs = request.isolationForestScore();
            if (ifs != null && ifs > ifAntiPoisonThreshold) {
                metrics.recordTrainingCandidatePublishSkippedGate();
                return;
            }
        }
        if (rate < 1.0) {
            boolean bypassSample = d.isTrainingPublishStratifiedSampling()
                && request.compositeScore() >= d.getTrainingPublishHighCompositeBypassSampleMinScore();
            if (!bypassSample && ThreadLocalRandom.current().nextDouble() >= rate) {
                metrics.recordTrainingCandidatePublishSkippedSample();
                return;
            }
        }
        RequestFeatures features = request.features();
        if (features == null) {
            return;
        }
        if (!inFlight.tryAcquire()) {
            metrics.recordTrainingCandidatePublishDropped();
            status.recordError("inflight_exhausted", null);
            return;
        }
        double[] isolationForestSnapshot = copyDoubles(features.toIsolationForestArray(), IF_FEATURE_LEN);
        double[] statisticalSnapshot = copyDoubles(features.toArray(), STAT_FEATURE_LEN);
        String endpointSha256Hex = TrainingFingerprintHashes.sha256HexUtf8(features.endpoint());
        String enforcementKeyPlain = EnforcementKeys.enforcementKey(
            request.enforcementScope(),
            features.identityHash(),
            features.endpoint());
        String enforcementKeySha256Hex = TrainingFingerprintHashes.sha256HexUtf8(enforcementKeyPlain);
        String eventId = UUID.randomUUID().toString();
        long observedAt = System.currentTimeMillis();
        TrainingCandidateRecord record = new TrainingCandidateRecord(
            TrainingCandidateRecord.CURRENT_SCHEMA_VERSION,
            eventId,
            request.tenantId(),
            request.nodeId(),
            features.identityHash(),
            endpointSha256Hex,
            enforcementKeySha256Hex,
            observedAt,
            isolationForestSnapshot,
            statisticalSnapshot,
            request.statisticalScore(),
            request.isolationForestScore(),
            request.compositeScore(),
            request.policyAction().name(),
            request.sentinelMode(),
            request.requestProceeded(),
            request.startupGraceActive()
        );
        try {
            executor.execute(() -> {
                long t0 = System.nanoTime();
                try {
                    metrics.recordTrainingCandidatePublishAttempt();
                    transport.send(record);
                    metrics.recordTrainingCandidatePublishSuccess();
                    metrics.recordTrainingCandidatePublishTransportDurationNanos(System.nanoTime() - t0);
                    status.recordSuccess();
                } catch (Exception e) {
                    metrics.recordTrainingCandidatePublishTransportDurationNanos(System.nanoTime() - t0);
                    TrainingPublishFailureKind kind = TrainingPublishFailureKind.classify(e);
                    switch (kind) {
                        case KAFKA_TIMEOUT -> {
                            metrics.recordTrainingCandidatePublishFailureTimeout();
                            status.recordError("kafka_timeout", e);
                        }
                        case SERIALIZATION -> {
                            metrics.recordTrainingCandidatePublishFailureSerialization();
                            status.recordError("serialize_failed", e);
                        }
                        default -> {
                            metrics.recordTrainingCandidatePublishFailure();
                            status.recordError("transport", e);
                        }
                    }
                    log.debug("Training candidate transport failed: {}", e.toString());
                } finally {
                    inFlight.release();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.release();
            metrics.recordTrainingCandidatePublishExecutorRejected();
            status.recordError("executor_rejected", e);
            log.debug("Training candidate task rejected: {}", e.toString());
        }
    }

    private static double[] copyDoubles(double[] src, int len) {
        double[] out = new double[len];
        if (src != null) {
            int n = Math.min(len, src.length);
            System.arraycopy(src, 0, out, 0, n);
        }
        return out;
    }

    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
