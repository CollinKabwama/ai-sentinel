package io.aisentinel.core.enforcement;

import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import io.aisentinel.core.telemetry.TelemetryEvent;
import io.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import io.aisentinel.distributed.quarantine.NoopClusterQuarantineWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Composite enforcement handler with Allow, Monitor, Throttle, Block, Quarantine.
 * Throttle and quarantine maps are bounded by maxKeys and TTL eviction.
 */
@Slf4j
public final class CompositeEnforcementHandler implements EnforcementHandler {

    private final int blockStatusCode;
    private final long quarantineDurationMs;
    private final double throttleRequestsPerSecond;
    private final Map<String, AtomicLong> throttleTokens = new ConcurrentHashMap<>();
    private final Map<String, Long> quarantinedUntil = new ConcurrentHashMap<>();
    private final TelemetryEmitter telemetry;
    private final int maxKeys;
    private final long throttleTtlMs;
    private final EnforcementScope enforcementScope;
    private final ClusterQuarantineWriter clusterQuarantineWriter;
    private final String distributedTenantId;

    public CompositeEnforcementHandler(int blockStatusCode, long quarantineDurationMs,
                                       double throttleRequestsPerSecond, TelemetryEmitter telemetry) {
        this(blockStatusCode, quarantineDurationMs, throttleRequestsPerSecond, telemetry, 100_000, 300_000L,
            EnforcementScope.IDENTITY_ENDPOINT, NoopClusterQuarantineWriter.INSTANCE, "default");
    }

    public CompositeEnforcementHandler(int blockStatusCode, long quarantineDurationMs,
                                       double throttleRequestsPerSecond, TelemetryEmitter telemetry,
                                       int maxKeys, long throttleTtlMs) {
        this(blockStatusCode, quarantineDurationMs, throttleRequestsPerSecond, telemetry, maxKeys, throttleTtlMs,
            EnforcementScope.IDENTITY_ENDPOINT, NoopClusterQuarantineWriter.INSTANCE, "default");
    }

    public CompositeEnforcementHandler(int blockStatusCode, long quarantineDurationMs,
                                       double throttleRequestsPerSecond, TelemetryEmitter telemetry,
                                       int maxKeys, long throttleTtlMs, EnforcementScope enforcementScope) {
        this(blockStatusCode, quarantineDurationMs, throttleRequestsPerSecond, telemetry, maxKeys, throttleTtlMs,
            enforcementScope, NoopClusterQuarantineWriter.INSTANCE, "default");
    }

    /**
     * @param clusterQuarantineWriter optional cluster replication (defaults to noop); must not block the request thread
     * @param distributedTenantId tenant segment for {@link ClusterQuarantineWriter#publishQuarantine}
     */
    public CompositeEnforcementHandler(int blockStatusCode, long quarantineDurationMs,
                                       double throttleRequestsPerSecond, TelemetryEmitter telemetry,
                                       int maxKeys, long throttleTtlMs, EnforcementScope enforcementScope,
                                       ClusterQuarantineWriter clusterQuarantineWriter, String distributedTenantId) {
        this.blockStatusCode = blockStatusCode;
        this.quarantineDurationMs = quarantineDurationMs;
        this.throttleRequestsPerSecond = Math.max(0.1, throttleRequestsPerSecond);
        this.telemetry = telemetry;
        this.maxKeys = Math.max(1, maxKeys);
        this.throttleTtlMs = Math.max(1000L, throttleTtlMs);
        this.enforcementScope = enforcementScope != null ? enforcementScope : EnforcementScope.IDENTITY_ENDPOINT;
        this.clusterQuarantineWriter = clusterQuarantineWriter != null
            ? clusterQuarantineWriter
            : NoopClusterQuarantineWriter.INSTANCE;
        this.distributedTenantId = distributedTenantId != null && !distributedTenantId.isBlank()
            ? distributedTenantId
            : "default";
    }

    private String buildEnforcementStateKey(String identityHash, String endpoint) {
        if (enforcementScope == EnforcementScope.IDENTITY_GLOBAL) {
            return identityHash;
        }
        return identityHash + "|" + (endpoint != null ? endpoint : "");
    }

    @Override
    public boolean apply(EnforcementAction action, HttpServletRequest request, HttpServletResponse response,
                         String identityHash, String endpoint) {
        return switch (action) {
            case ALLOW -> true;
            case MONITOR -> {
                telemetry.emit(TelemetryEvent.policyActionApplied(identityHash, endpoint, "MONITOR", null));
                yield true;
            }
            case THROTTLE -> applyThrottle(request, response, identityHash, endpoint);
            case BLOCK -> {
                applyBlock(response, identityHash, endpoint);
                yield false;
            }
            case QUARANTINE -> {
                applyQuarantine(response, identityHash, endpoint);
                yield false;
            }
        };
    }

    @Override
    public boolean isQuarantined(String identityHash, String endpoint) {
        String key = buildEnforcementStateKey(identityHash, endpoint);
        long now = System.currentTimeMillis();
        Long until = quarantinedUntil.compute(key, (k, v) -> {
            if (v == null) return null;
            if (now > v) return null;
            return v;
        });
        return until != null;
    }

    /** Current count of identities (or identity+endpoint keys) in quarantine (for actuator / monitor visibility). */
    public int getQuarantineCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Long until : quarantinedUntil.values()) {
            if (until != null && until > now) n++;
        }
        return n;
    }

    /** Approximate number of throttle token buckets currently tracked. */
    public int getThrottleCount() {
        return throttleTokens.size();
    }

    public boolean tryAcquireThrottlePermit(String identityHash, String endpoint) {
        String key = buildEnforcementStateKey(identityHash, endpoint);
        evictThrottleIfNeeded();
        long now = System.nanoTime();
        long refillNs = (long) (1_000_000_000.0 / throttleRequestsPerSecond);
        AtomicLong nextAllowed = throttleTokens.computeIfAbsent(key, k -> new AtomicLong(0));
        for (; ; ) {
            long prev = nextAllowed.get();
            if (now < prev) return false;
            if (nextAllowed.compareAndSet(prev, now + refillNs)) return true;
        }
    }

    private void evictThrottleIfNeeded() {
        if (throttleTokens.size() <= maxKeys) return;
        long cutoffNs = System.nanoTime() - throttleTtlMs * 1_000_000;
        for (Iterator<Map.Entry<String, AtomicLong>> it = throttleTokens.entrySet().iterator(); it.hasNext(); ) {
            if (throttleTokens.size() <= maxKeys) break;
            Map.Entry<String, AtomicLong> e = it.next();
            if (e.getValue().get() < cutoffNs) {
                it.remove();
            }
        }
        while (throttleTokens.size() > maxKeys) {
            Iterator<Map.Entry<String, AtomicLong>> it = throttleTokens.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            } else break;
        }
    }

    private void evictQuarantineIfNeeded() {
        if (quarantinedUntil.size() <= maxKeys) return;
        long now = System.currentTimeMillis();
        quarantinedUntil.entrySet().removeIf(e -> e.getValue() < now);
        while (quarantinedUntil.size() > maxKeys) {
            String victim = null;
            Long minUntil = null;
            for (Map.Entry<String, Long> e : quarantinedUntil.entrySet()) {
                if (minUntil == null || e.getValue() < minUntil) {
                    minUntil = e.getValue();
                    victim = e.getKey();
                }
            }
            if (victim == null) break;
            quarantinedUntil.remove(victim);
        }
    }

    private boolean applyThrottle(HttpServletRequest request, HttpServletResponse response,
                                  String identityHash, String endpoint) {
        if (!tryAcquireThrottlePermit(identityHash, endpoint)) {
            try {
                response.setStatus(429);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Too Many Requests");
            } catch (Exception ignored) { }
            telemetry.emit(TelemetryEvent.policyActionApplied(identityHash, endpoint, "THROTTLE_APPLIED", "429"));
            return false;
        }
        telemetry.emit(TelemetryEvent.policyActionApplied(identityHash, endpoint, "THROTTLE_ALLOW", null));
        return true;
    }

    private void applyBlock(HttpServletResponse response, String identityHash, String endpoint) {
        log.debug("Blocking request for endpoint={} identityHash={}", endpoint, maskHash(identityHash));
        try {
            response.setStatus(blockStatusCode);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(blockStatusCode == 403 ? "Forbidden" : "Too Many Requests");
        } catch (Exception ignored) { }
        telemetry.emit(TelemetryEvent.policyActionApplied(identityHash, endpoint, "BLOCK", String.valueOf(blockStatusCode)));
    }

    private void applyQuarantine(HttpServletResponse response, String identityHash, String endpoint) {
        log.debug("Quarantining identityHash={} for endpoint={} durationMs={}", maskHash(identityHash), endpoint, quarantineDurationMs);
        evictQuarantineIfNeeded();
        String key = buildEnforcementStateKey(identityHash, endpoint);
        long until = System.currentTimeMillis() + quarantineDurationMs;
        quarantinedUntil.put(key, until);
        try {
            clusterQuarantineWriter.publishQuarantine(distributedTenantId, key, until);
        } catch (RuntimeException e) {
            log.debug("Cluster quarantine publish failed after local quarantine applied; ignoring", e);
        }
        try {
            response.setStatus(blockStatusCode);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Quarantined");
        } catch (Exception ignored) { }
        telemetry.emit(TelemetryEvent.quarantineStarted(identityHash, endpoint, quarantineDurationMs));
    }

    private static String maskHash(String h) {
        if (h == null || h.length() < 8) return "***";
        return h.substring(0, 4) + "***" + h.substring(h.length() - 4);
    }
}
