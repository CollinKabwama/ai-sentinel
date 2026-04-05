package io.aisentinel.autoconfigure.model;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.scoring.IsolationForestScorer;
import io.aisentinel.model.ModelArtifactMetadata;
import io.aisentinel.model.ModelRegistryReader;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Off-request poll of {@link ModelRegistryReader}; installs newer artifacts via
 * {@link IsolationForestScorer#tryInstallFromRegistry} without blocking HTTP handling.
 */
@Slf4j
public final class ModelRefreshScheduler implements DisposableBean {

    private final IsolationForestScorer scorer;
    private final ModelRegistryReader registryReader;
    private final SentinelProperties props;
    private final SentinelMetrics metrics;
    private ScheduledExecutorService executor;

    public ModelRefreshScheduler(IsolationForestScorer scorer,
                                 ModelRegistryReader registryReader,
                                 SentinelProperties props,
                                 SentinelMetrics metrics) {
        this.scorer = scorer;
        this.registryReader = registryReader;
        this.props = props;
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
    }

    @PostConstruct
    public void start() {
        long intervalMs = props.getModelRegistry().getPollInterval() != null
            ? props.getModelRegistry().getPollInterval().toMillis()
            : 300_000L;
        intervalMs = Math.max(10_000L, intervalMs);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aisentinel-model-registry-refresh");
            t.setDaemon(true);
            return t;
        });
        executor.execute(this::tickSafe);
        executor.scheduleAtFixedRate(this::tickSafe, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Model registry refresh: immediate off-thread tick plus every {} ms", intervalMs);
    }

    private void tickSafe() {
        try {
            tick();
        } catch (Exception e) {
            log.debug("Model registry refresh tick failed: {}", e.toString());
            metrics.recordModelRegistryRefreshFailure();
        }
    }

    void tick() {
        metrics.recordModelRegistryRefreshAttempt();
        String tenant = props.getDistributed().getTenantId();
        Optional<ModelArtifactMetadata> meta = registryReader.resolveActiveMetadata(tenant);
        if (meta.isEmpty()) {
            return;
        }
        ModelArtifactMetadata m = meta.get();
        if (m.modelVersion().equals(scorer.getRegistryArtifactVersion()) && scorer.isModelLoaded()) {
            metrics.recordModelRegistryRefreshSkippedSameVersion();
            return;
        }
        Optional<byte[]> payload = registryReader.fetchPayload(tenant, m.modelVersion());
        if (payload.isEmpty()) {
            log.debug("Registry active={} but payload missing for tenant={}", m.modelVersion(), tenant);
            metrics.recordModelRegistryRefreshFailure();
            return;
        }
        boolean ok = scorer.tryInstallFromRegistry(m, payload.get());
        if (ok) {
            metrics.recordModelRegistryRefreshSuccess();
        } else {
            metrics.recordModelRegistryRefreshFailure();
        }
    }

    @Override
    public void destroy() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
