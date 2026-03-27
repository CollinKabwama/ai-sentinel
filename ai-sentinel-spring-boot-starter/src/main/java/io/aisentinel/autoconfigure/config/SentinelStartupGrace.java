package io.aisentinel.autoconfigure.config;

import io.aisentinel.core.runtime.StartupGrace;

import java.time.Duration;

/**
 * Wall-clock grace window from bean creation (typically application startup).
 */
public final class SentinelStartupGrace implements StartupGrace {

    private final long startMillis = System.currentTimeMillis();
    private final long graceMillis;

    public SentinelStartupGrace(Duration gracePeriod) {
        this.graceMillis = gracePeriod != null && !gracePeriod.isNegative() ? gracePeriod.toMillis() : 0L;
    }

    @Override
    public boolean isGraceActive() {
        return graceMillis > 0 && (System.currentTimeMillis() - startMillis) < graceMillis;
    }
}
