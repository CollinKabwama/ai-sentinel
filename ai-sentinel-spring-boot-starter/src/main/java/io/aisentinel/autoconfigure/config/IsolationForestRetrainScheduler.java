package io.aisentinel.autoconfigure.config;

import io.aisentinel.core.scoring.IsolationForestScorer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Schedules periodic retraining of Isolation Forest when enabled.
 * Runs in a single background thread; does not block the request path.
 */
@Slf4j
public class IsolationForestRetrainScheduler implements DisposableBean {

    private final IsolationForestScorer scorer;
    private final SentinelProperties props;
    private ScheduledExecutorService executor;

    public IsolationForestRetrainScheduler(IsolationForestScorer scorer, SentinelProperties props) {
        this.scorer = scorer;
        this.props = props;
    }

    @PostConstruct
    public void start() {
        long intervalMs = props.getIsolationForest().getRetrainInterval() != null
            ? props.getIsolationForest().getRetrainInterval().toMillis()
            : 300_000L;
        intervalMs = Math.max(60_000L, intervalMs);
        long initialDelayMs = Math.min(intervalMs, 30_000L);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aisentinel-if-retrain");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::runRetrain, initialDelayMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("Isolation Forest retrain scheduled every {} ms (first run in {} ms)", intervalMs, initialDelayMs);
    }

    private void runRetrain() {
        try {
            scorer.retrain();
        } catch (Exception e) {
            log.warn("Isolation Forest retrain failed (request path unaffected): {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
