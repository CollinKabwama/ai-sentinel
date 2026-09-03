package dev.aisentinel.core.feature;

import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.model.IdentityEndpointKey;
import dev.aisentinel.core.store.BaselineStore;
import dev.aisentinel.core.http.HttpRequestView;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.regex.Pattern;

/**
 * Default feature extractor using privacy-safe features:
 * requestsPerWindow, endpointEntropy, endpointConcentration, tokenAgeSeconds, parameterCount,
 * payloadSizeBytes, headerFingerprintHash, ipBucket.
 * Endpoint history uses atomic increments, safe indexing (no Math.abs(Integer.MIN_VALUE)), and bounded map with TTL.
 * <p>
 * Shannon entropy remains a diversity-only signal. Endpoint concentration (max histogram share) is
 * computed separately for diverse→mono distribution shifts. Neither signal distinguishes established
 * mono-endpoint use from mono-endpoint flooding; volume floods use {@code requestsPerWindow}.
 */
public final class DefaultFeatureExtractor implements FeatureExtractor {

    private static final int HISTORY_SIZE = 16;
    /** Saturate at max to avoid overflow; use int max - 1 so sum of 16 slots can't overflow. */
    private static final int MAX_HISTORY_COUNT = Integer.MAX_VALUE - 1;
    private static final Pattern UUID_SEGMENT = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern NUMERIC_SEGMENT = Pattern.compile("^[0-9]+$");

    private final BaselineStore requestCountStore;
    private final Map<String, EndpointHistoryEntry> endpointHistory;
    private final int maxKeys;
    private final long ttlMs;

    public DefaultFeatureExtractor(BaselineStore requestCountStore) {
        this(requestCountStore, 100_000, 300_000L);
    }

    public DefaultFeatureExtractor(BaselineStore requestCountStore, int maxKeys, long ttlMs) {
        this.requestCountStore = requestCountStore;
        this.endpointHistory = new ConcurrentHashMap<>();
        this.maxKeys = Math.max(1, maxKeys);
        this.ttlMs = Math.max(1000L, ttlMs);
    }

    @Override
    public RequestFeatures extract(HttpRequestView request, String identityHash, RequestContext ctx) {
        long now = System.currentTimeMillis();
        String endpoint = normalizeEndpoint(request.getRequestURI());
        IdentityEndpointKey stateKey = IdentityEndpointKey.forEndpoint(identityHash, endpoint);

        int requestsPerWindow = requestCountStore.incrementAndGet(stateKey);
        EndpointDiversity diversity = computeEndpointDiversity(identityHash, endpoint, now);
        double tokenAgeSeconds = extractTokenAgeSeconds(request, now);
        int parameterCount = request.getParameterMap().size();
        long payloadSizeBytes = extractPayloadSize(request);
        long headerFingerprintHash = computeHeaderFingerprint(request);
        int ipBucket = extractIpBucket(request);

        return RequestFeatures.builder()
            .identityHash(identityHash)
            .endpoint(endpoint)
            .timestampMillis(now)
            .requestsPerWindow(requestsPerWindow)
            .endpointEntropy(diversity.entropy())
            .endpointConcentration(diversity.concentration())
            .tokenAgeSeconds(tokenAgeSeconds)
            .parameterCount(parameterCount)
            .payloadSizeBytes(payloadSizeBytes)
            .headerFingerprintHash(headerFingerprintHash)
            .ipBucket(ipBucket)
            .build();
    }

    private String normalizeEndpoint(String uri) {
        if (uri == null || uri.isEmpty()) return "/";
        String path = uri.length() > 256 ? uri.substring(0, 256) : uri;
        return normalizePathParams(path);
    }

    /** Replaces path parameter segments (numeric, UUID) with {id} to prevent map explosion. */
    static String normalizePathParams(String path) {
        if (path == null || path.isEmpty()) return "/";
        String[] segments = path.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.isEmpty()) continue;
            if (NUMERIC_SEGMENT.matcher(seg).matches() || UUID_SEGMENT.matcher(seg).matches()) {
                segments[i] = "{id}";
            }
        }
        return String.join("/", segments);
    }

    /**
     * Safe index in [0, HISTORY_SIZE): avoids Math.abs(Integer.MIN_VALUE) which stays negative.
     */
    private static int safeHistoryIndex(String endpoint) {
        int h = endpoint.hashCode();
        int mod = (h % HISTORY_SIZE + HISTORY_SIZE) % HISTORY_SIZE;
        return mod;
    }

    private EndpointDiversity computeEndpointDiversity(String identityHash, String endpoint, long nowMs) {
        EndpointHistoryEntry entry = endpointHistory.computeIfAbsent(identityHash, k -> new EndpointHistoryEntry());
        entry.lastAccessMs = nowMs;

        int index = safeHistoryIndex(endpoint);
        AtomicIntegerArray arr = entry.counts;
        int v = arr.getAndIncrement(index);
        if (v >= MAX_HISTORY_COUNT) {
            arr.decrementAndGet(index);
        }

        int total = 0;
        int maxCount = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            int c = arr.get(i);
            total += c;
            if (c > maxCount) {
                maxCount = c;
            }
        }
        if (total == 0) {
            evictEndpointHistoryIfNeeded(nowMs);
            return new EndpointDiversity(0.0, 0.0);
        }
        double entropy = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            int c = arr.get(i);
            if (c > 0) {
                double p = (double) c / total;
                entropy -= p * Math.log(p);
            }
        }
        double concentration = (double) maxCount / total;
        evictEndpointHistoryIfNeeded(nowMs);
        return new EndpointDiversity(entropy, concentration);
    }

    private record EndpointDiversity(double entropy, double concentration) {}

    private void evictEndpointHistoryIfNeeded(long now) {
        if (endpointHistory.size() <= maxKeys) return;
        long cutoff = now - ttlMs;
        endpointHistory.entrySet().removeIf(e -> e.getValue().lastAccessMs < cutoff);
        while (endpointHistory.size() > maxKeys) {
            String victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, EndpointHistoryEntry> e : endpointHistory.entrySet()) {
                long la = e.getValue().lastAccessMs;
                if (la < oldest) {
                    oldest = la;
                    victim = e.getKey();
                }
            }
            if (victim == null) break;
            endpointHistory.remove(victim);
        }
    }

    private static final class EndpointHistoryEntry {
        final AtomicIntegerArray counts = new AtomicIntegerArray(HISTORY_SIZE);
        volatile long lastAccessMs;
    }

    private double extractTokenAgeSeconds(HttpRequestView request, long nowMillis) {
        String auth = request.getHeader("Authorization");
        if (auth == null || auth.isBlank()) {
            return TOKEN_AGE_MISSING_OR_INVALID;
        }
        String issuedStr = request.getHeader("X-Token-Issued-At");
        if (issuedStr == null || issuedStr.isBlank()) {
            return TOKEN_AGE_MISSING_OR_INVALID;
        }
        try {
            long issuedEpochSeconds = Long.parseLong(issuedStr.trim());
            // Avoid overflow when converting seconds → millis for extreme inputs.
            if (issuedEpochSeconds > Long.MAX_VALUE / 1000L || issuedEpochSeconds < Long.MIN_VALUE / 1000L) {
                return TOKEN_AGE_MISSING_OR_INVALID;
            }
            long issuedMs = issuedEpochSeconds * 1000L;
            double ageSeconds = (nowMillis - issuedMs) / 1000.0;
            if (ageSeconds < 0) {
                // Small future offsets are ordinary issuer/application clock skew: treat as
                // freshly issued (0) rather than conflating with missing/invalid (-1).
                // Materially future timestamps are not clock skew — an unbounded clamp to 0
                // lets a client fully neutralize this feature (verified: against an established
                // near-zero-token-age baseline, an arbitrarily-future timestamp collapsed a score
                // that would otherwise saturate to ~1.0 down to ~0.05, an ALLOW-band result, using
                // only a spoofed header). Beyond the tolerated skew window, treat the same as
                // missing/invalid so the value cannot be steered to look artificially fresh.
                if (ageSeconds >= -MAX_TOLERATED_FUTURE_SKEW_SECONDS) {
                    return TOKEN_AGE_FUTURE_CLAMPED;
                }
                return TOKEN_AGE_MISSING_OR_INVALID;
            }
            return ageSeconds;
        } catch (NumberFormatException e) {
            return TOKEN_AGE_MISSING_OR_INVALID;
        }
    }

    /** Missing Authorization / issued-at, blank, unparsable, overflow, or future beyond tolerated skew. */
    static final double TOKEN_AGE_MISSING_OR_INVALID = -1.0;
    /** Future {@code X-Token-Issued-At} within tolerated clock skew, clamped so it is distinguishable
     *  from missing/invalid. */
    static final double TOKEN_AGE_FUTURE_CLAMPED = 0.0;
    /**
     * Ordinary issuer/application clock skew tolerance. Matches common JWT-library leeway defaults
     * (tens of seconds to a few minutes); not configurable to avoid a footgun that reintroduces the
     * unbounded-clamp manipulability this constant closes.
     */
    static final long MAX_TOLERATED_FUTURE_SKEW_SECONDS = 300L;

    private long extractPayloadSize(HttpRequestView request) {
        String cl = request.getHeader("Content-Length");
        if (cl == null) return 0;
        try {
            return Long.parseLong(cl.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long computeHeaderFingerprint(HttpRequestView request) {
        Map<String, String> h = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return 0;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name != null && !name.equalsIgnoreCase("Authorization")) {
                String v = request.getHeader(name);
                h.put(name.toLowerCase(Locale.ROOT), v != null ? Integer.toString(v.length()) : "0");
            }
        }
        return h.hashCode();
    }

    @Override
    public int metricsEndpointHistoryEntryCount() {
        return endpointHistory.size();
    }

    private int extractIpBucket(HttpRequestView request) {
        String ip = request.getRemoteAddr();
        if (ip == null) return 0;
        if (ip.contains(":")) {
            return ip.hashCode() & 0x7FFF_FFFF;
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            try {
                int a = Integer.parseInt(parts[0]) & 0xFF;
                int b = Integer.parseInt(parts[1]) & 0xFF;
                int c = Integer.parseInt(parts[2]) & 0xFF;
                return (a << 16) | (b << 8) | c;
            } catch (NumberFormatException e) { /* fall through */ }
        }
        return ip.hashCode() & 0x7FFF_FFFF;
    }
}
