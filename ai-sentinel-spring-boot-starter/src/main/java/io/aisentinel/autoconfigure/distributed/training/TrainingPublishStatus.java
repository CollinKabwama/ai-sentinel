package io.aisentinel.autoconfigure.distributed.training;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable snapshot for training candidate export health (actuator / ops).
 */
public final class TrainingPublishStatus {

    private final AtomicLong lastErrorTimeMillis = new AtomicLong(0);
    private volatile String lastErrorSummary = "";
    private volatile boolean degraded;

    public void recordSuccess() {
        degraded = false;
    }

    public void recordError(String kind, Throwable t) {
        degraded = true;
        lastErrorTimeMillis.set(System.currentTimeMillis());
        String msg = t != null ? t.getClass().getSimpleName() : "unknown";
        if (kind != null && !kind.isBlank()) {
            lastErrorSummary = kind + ": " + msg;
        } else {
            lastErrorSummary = msg;
        }
        if (lastErrorSummary.length() > 256) {
            lastErrorSummary = lastErrorSummary.substring(0, 256);
        }
    }

    public long getLastErrorTimeMillis() {
        return lastErrorTimeMillis.get();
    }

    public String getLastErrorSummary() {
        return lastErrorSummary;
    }

    public boolean isDegraded() {
        return degraded;
    }
}
