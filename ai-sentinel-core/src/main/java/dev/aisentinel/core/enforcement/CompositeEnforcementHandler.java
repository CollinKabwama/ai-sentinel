package dev.aisentinel.core.enforcement;

import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import dev.aisentinel.distributed.quarantine.NoopClusterQuarantineWriter;
import dev.aisentinel.distributed.throttle.ClusterThrottleStore;
import dev.aisentinel.distributed.throttle.NoopClusterThrottleStore;
import dev.aisentinel.core.http.HttpRequestView;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
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
    private final ClusterThrottleStore clusterThrottleStore;
    private final String distributedTenantId;
    private final SentinelMetrics metrics;

    public CompositeEnforcementHandler(int blockStatusCode, long quarantineDurationMs,
                                       double throttleRequestsPerSecond, TelemetryEmitter telemetry) {
        this(blockStatusCode, quarantineDurationMs, throttleRequestsPerSecond, telemetry, 100_000, 300_000L,
            EnforcementScope.IDENTITY_ENDPOINT, NoopClusterQuarantineWriter.INSTANCE,
            NoopClusterThrottleStore.INSTANCE, "default", SentinelMetrics.NOOP);
    }

    public CompositeEnforcementHandler(int blockStatusCode, long quarantineDurationMs,
                                       double throttleRequestsPerSecond, TelemetryEmitter telemetry,
                                       int maxKeys, long throttleTtlMs) {
        this(blockStatusCode, quarantineDurationMs, throttleRequestsPerSecond, telemetry, maxKeys, throttleTtlMs,
            EnforcementScope.IDENTITY_ENDPOINT, NoopClusterQuarantineWriter.INSTANCE,
            NoopClusterThrottleStore.INSTANCE, "default", SentinelMetrics.NOOP);
    }

    public CompositeEnforcementHandler(int blockStatusCode, long quarantineDurationMs,
                                       double throttleRequestsPerSecond, TelemetryEmitter telemetry,
                                       int maxKeys, long throttleTtlMs, EnforcementScope enforcementScope) {
        this(blockStatusCode, quarantineDurationMs, throttleRequestsPerSecond, telemetry, maxKeys, throttleTtlMs,
            enforcementScope, NoopClusterQuarantineWriter.INSTANCE, NoopClusterThrottleStore.INSTANCE, "default",
            SentinelMetrics.NOOP);
    }

    /**
     * Same as {@link #CompositeEnforcementHandler(int, long, double, TelemetryEmitter, int, long, EnforcementScope, ClusterQuarantineWriter, ClusterThrottleStore, String, SentinelMetrics)}
     * with {@link NoopClusterThrottleStore} and {@link SentinelMetrics#NOOP}.
     */
    public CompositeEnforcementHandler(int blockStatusCode, long quarantineDurationMs,
                                       double throttleRequestsPerSecond, TelemetryEmitter telemetry,
                                       int maxKeys, long throttleTtlMs, EnforcementScope enforcementScope,
                                       ClusterQuarantineWriter clusterQuarantineWriter, String distributedTenantId) {
        this(blockStatusCode, quarantineDurationMs, throttleRequestsPerSecond, telemetry, maxKeys, throttleTtlMs,
            enforcementScope, clusterQuarantineWriter, NoopClusterThrottleStore.INSTANCE, distributedTenantId,
            SentinelMetrics.NOOP);
    }

    /**
     * Same as the full constructor with {@link SentinelMetrics#NOOP}.
     */
    public CompositeEnforcementHandler(int blockStatusCode, long quarantineDurationMs,
                                       double throttleRequestsPerSecond, TelemetryEmitter telemetry,
                                       int maxKeys, long throttleTtlMs, EnforcementScope enforcementScope,
                                       ClusterQuarantineWriter clusterQuarantineWriter,
                                       ClusterThrottleStore clusterThrottleStore,
                                       String distributedTenantId) {
        this(blockStatusCode, quarantineDurationMs, throttleRequestsPerSecond, telemetry, maxKeys, throttleTtlMs,
            enforcementScope, clusterQuarantineWriter, clusterThrottleStore, distributedTenantId,
            SentinelMetrics.NOOP);
    }

    /**
     * @param clusterQuarantineWriter optional cluster replication (defaults to noop); must not block the request thread
     * @param clusterThrottleStore optional cluster throttle (defaults to noop); evaluated before local throttle bucket
     * @param distributedTenantId tenant segment for {@link ClusterQuarantineWriter#publishQuarantine}
     * @param metrics optional metrics (defaults to noop)
     */
    public CompositeEnforcementHandler(int blockStatusCode, long quarantineDurationMs,
                                       double throttleRequestsPerSecond, TelemetryEmitter telemetry,
                                       int maxKeys, long throttleTtlMs, EnforcementScope enforcementScope,
                                       ClusterQuarantineWriter clusterQuarantineWriter,
                                       ClusterThrottleStore clusterThrottleStore,
                                       String distributedTenantId,
                                       SentinelMetrics metrics) {
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
        this.clusterThrottleStore = clusterThrottleStore != null
            ? clusterThrottleStore
            : NoopClusterThrottleStore.INSTANCE;
        this.distributedTenantId = distributedTenantId != null && !distributedTenantId.isBlank()
            ? distributedTenantId
            : "default";
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;
    }

    private String buildEnforcementStateKey(String identityHash, String endpoint) {
        if (enforcementScope == EnforcementScope.IDENTITY_GLOBAL) {
            return identityHash;
        }
        return identityHash + "|" + (endpoint != null ? endpoint : "");
    }

    @Override
    public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                         String identityHash, String endpoint) {
        return switch (action) {
            case ALLOW -> true;
            case MONITOR -> {
                telemetry.emit(TelemetryEvent.policyActionApplied(identityHash, endpoint, "MONITOR", null));
                yield true;
            }
            case THROTTLE -> applyThrottle(response, identityHash, endpoint);
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

    /**
     * Targeted local quarantine release plus best-effort cluster clear for the same enforcement key.
     * Idempotent: missing local entry returns {@code false} but still attempts cluster clear.
     * Does not reset baselines or create exemptions.
     */
    @Override
    public boolean releaseQuarantine(String identityHash, String endpoint) {
        String key = buildEnforcementStateKey(identityHash, endpoint);
        Long removed = quarantinedUntil.remove(key);
        boolean hadLocal = removed != null;
        try {
            clusterQuarantineWriter.clearQuarantine(distributedTenantId, key);
        } catch (RuntimeException e) {
            log.debug("Cluster quarantine clear failed after local release; ignoring", e);
            metrics.recordDistributedQuarantineClearFailure();
        }
        metrics.recordQuarantineReleased(hadLocal);
        telemetry.emit(TelemetryEvent.quarantineReleased(identityHash, endpoint, hadLocal));
        return hadLocal;
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
        if (!clusterThrottleStore.tryAcquire(distributedTenantId, key)) {
            return false;
        }
        evictThrottleIfNeeded();
        long now = System.nanoTime();
        long refillNs = (long) (1_000_000_000.0 / throttleRequestsPerSecond);
        // Perform the token CAS under ConcurrentHashMap.compute so a concurrent capacity
        // eviction cannot remove the map entry mid-update and leave an orphaned AtomicLong.
        boolean[] allowed = {false};
        throttleTokens.compute(key, (k, existing) -> {
            AtomicLong nextAllowed = existing != null ? existing : new AtomicLong(0);
            for (; ; ) {
                long prev = nextAllowed.get();
                if (now < prev) {
                    allowed[0] = false;
                    break;
                }
                if (nextAllowed.compareAndSet(prev, now + refillNs)) {
                    allowed[0] = true;
                    break;
                }
            }
            return nextAllowed;
        });
        return allowed[0];
    }

    private void evictThrottleIfNeeded() {
        if (throttleTokens.size() <= maxKeys) {
            return;
        }
        long cutoffNs = System.nanoTime() - throttleTtlMs * 1_000_000;
        for (Map.Entry<String, AtomicLong> e : List.copyOf(throttleTokens.entrySet())) {
            if (throttleTokens.size() <= maxKeys) {
                break;
            }
            AtomicLong observed = e.getValue();
            if (observed.get() < cutoffNs) {
                // Re-check under compute so a concurrent acquire that refreshed the bucket is kept.
                throttleTokens.compute(e.getKey(), (k, cur) -> {
                    if (cur == null) {
                        return null;
                    }
                    return cur.get() < cutoffNs ? null : cur;
                });
            }
        }
        int staleRetries = 0;
        while (throttleTokens.size() > maxKeys) {
            // Pick the bucket with the smallest nextAllowed as the victim: each successful acquire
            // advances nextAllowed to (now + refillNs), so the smallest value approximates the least
            // recently touched bucket (map-iteration order, used previously, has no relationship to
            // recency at all and could evict an actively-hammered key on every pass).
            Map.Entry<String, AtomicLong> victim = null;
            long victimValue = Long.MAX_VALUE;
            for (Map.Entry<String, AtomicLong> e : throttleTokens.entrySet()) {
                long v = e.getValue().get();
                if (v < victimValue) {
                    victimValue = v;
                    victim = e;
                }
            }
            if (victim == null) {
                break;
            }
            String victimKey = victim.getKey();
            // AtomicLong has no value-based equals(); the same instance is mutated in place by
            // concurrent tryAcquireThrottlePermit calls, so remove(key, atomicLongRef) matches on
            // object identity and would succeed even if the bucket was refreshed a moment ago.
            // Snapshot the numeric value and re-verify it is unchanged under compute before removing,
            // mirroring the TTL pass above.
            long observedValue = victim.getValue().get();
            boolean[] removed = {false};
            throttleTokens.compute(victimKey, (k, cur) -> {
                if (cur == null) {
                    return null;
                }
                if (cur.get() == observedValue) {
                    removed[0] = true;
                    return null;
                }
                return cur;
            });
            if (!removed[0]) {
                if (++staleRetries >= 8) {
                    throttleTokens.remove(victimKey);
                    staleRetries = 0;
                }
                continue;
            }
            staleRetries = 0;
        }
    }

    private void evictQuarantineIfNeeded() {
        if (quarantinedUntil.size() <= maxKeys) {
            return;
        }
        long now = System.currentTimeMillis();
        quarantinedUntil.entrySet().removeIf(e -> e.getValue() < now);
        int staleRetries = 0;
        while (quarantinedUntil.size() > maxKeys) {
            String victim = null;
            Long minUntil = null;
            for (Map.Entry<String, Long> e : quarantinedUntil.entrySet()) {
                if (minUntil == null || e.getValue() < minUntil) {
                    minUntil = e.getValue();
                    victim = e.getKey();
                }
            }
            if (victim == null || minUntil == null) {
                break;
            }
            // Conditional remove: a concurrent applyQuarantine may have extended this key's
            // until after the scan observed minUntil. Unconditional remove would drop live state.
            if (!quarantinedUntil.remove(victim, minUntil)) {
                if (++staleRetries >= 8) {
                    quarantinedUntil.remove(victim);
                    staleRetries = 0;
                }
                continue;
            }
            staleRetries = 0;
        }
    }

    private boolean applyThrottle(EnforcementResponse response, String identityHash, String endpoint) {
        if (!tryAcquireThrottlePermit(identityHash, endpoint)) {
            writeDenialResponse(response, 429, "Too Many Requests");
            telemetry.emit(TelemetryEvent.policyActionApplied(identityHash, endpoint, "THROTTLE_APPLIED", "429"));
            return false;
        }
        telemetry.emit(TelemetryEvent.policyActionApplied(identityHash, endpoint, "THROTTLE_ALLOW", null));
        return true;
    }

    private void applyBlock(EnforcementResponse response, String identityHash, String endpoint) {
        log.debug("Blocking request for endpoint={} identityHash={}", endpoint, maskHash(identityHash));
        String body = blockStatusCode == 403 ? "Forbidden" : "Too Many Requests";
        writeDenialResponse(response, blockStatusCode, body);
        telemetry.emit(TelemetryEvent.policyActionApplied(identityHash, endpoint, "BLOCK", String.valueOf(blockStatusCode)));
    }

    private void applyQuarantine(EnforcementResponse response, String identityHash, String endpoint) {
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
        writeDenialResponse(response, blockStatusCode, "Quarantined");
        telemetry.emit(TelemetryEvent.quarantineStarted(identityHash, endpoint, quarantineDurationMs));
    }

    /**
     * Best-effort client denial write. Skips mutation when the response is already committed
     * (local quarantine/throttle state and telemetry still apply). Does not introduce servlet types.
     */
    private void writeDenialResponse(EnforcementResponse response, int status, String body) {
        if (response.isCommitted()) {
            log.debug("Skipping enforcement HTTP write; response already committed (status would have been {})", status);
            return;
        }
        try {
            response.setStatus(status);
            response.setContentType("text/plain;charset=UTF-8");
            response.writeBody(body);
        } catch (Exception e) {
            log.debug("Enforcement HTTP write failed (response may have become committed): {}", e.toString());
        }
    }

    private static String maskHash(String h) {
        if (h == null || h.length() < 8) return "***";
        return h.substring(0, 4) + "***" + h.substring(h.length() - 4);
    }
}
