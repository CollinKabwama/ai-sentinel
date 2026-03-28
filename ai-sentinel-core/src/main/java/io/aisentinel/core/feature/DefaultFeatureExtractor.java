package io.aisentinel.core.feature;

import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.store.BaselineStore;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.regex.Pattern;

/**
 * Default feature extractor using privacy-safe features:
 * requestsPerWindow, endpointEntropy, tokenAgeSeconds, parameterCount,
 * payloadSizeBytes, headerFingerprintHash, ipBucket.
 * Endpoint history uses atomic increments, safe indexing (no Math.abs(Integer.MIN_VALUE)), and bounded map with TTL.
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
    public RequestFeatures extract(HttpServletRequest request, String identityHash, RequestContext ctx) {
        long now = System.currentTimeMillis();
        String endpoint = normalizeEndpoint(request.getRequestURI());

        int requestsPerWindow = requestCountStore.incrementAndGet(identityHash + "|" + endpoint);
        double endpointEntropy = computeEndpointEntropy(identityHash, endpoint, now);
        double tokenAgeSeconds = extractTokenAgeSeconds(request);
        int parameterCount = request.getParameterMap().size();
        long payloadSizeBytes = extractPayloadSize(request);
        long headerFingerprintHash = computeHeaderFingerprint(request);
        int ipBucket = extractIpBucket(request);

        return RequestFeatures.builder()
            .identityHash(identityHash)
            .endpoint(endpoint)
            .timestampMillis(now)
            .requestsPerWindow(requestsPerWindow)
            .endpointEntropy(endpointEntropy)
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

    private double computeEndpointEntropy(String identityHash, String endpoint, long nowMs) {
        EndpointHistoryEntry entry = endpointHistory.computeIfAbsent(identityHash, k -> new EndpointHistoryEntry());
        entry.lastAccessMs = nowMs;

        int index = safeHistoryIndex(endpoint);
        AtomicIntegerArray arr = entry.counts;
        int v = arr.getAndIncrement(index);
        if (v >= MAX_HISTORY_COUNT) {
            arr.decrementAndGet(index);
        }

        int total = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            total += arr.get(i);
        }
        if (total == 0) return 0;
        double entropy = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            int c = arr.get(i);
            if (c > 0) {
                double p = (double) c / total;
                entropy -= p * Math.log(p + 1e-10);
            }
        }
        evictEndpointHistoryIfNeeded(nowMs);
        return entropy;
    }

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

    private double extractTokenAgeSeconds(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null) return -1;
        String issuedStr = request.getHeader("X-Token-Issued-At");
        if (issuedStr == null) return -1;
        try {
            long issued = Long.parseLong(issuedStr.trim()) * 1000L;
            return (System.currentTimeMillis() - issued) / 1000.0;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private long extractPayloadSize(HttpServletRequest request) {
        String cl = request.getHeader("Content-Length");
        if (cl == null) return 0;
        try {
            return Long.parseLong(cl.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long computeHeaderFingerprint(HttpServletRequest request) {
        Map<String, String> h = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) return 0;
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name != null && !name.equalsIgnoreCase("Authorization")) {
                String v = request.getHeader(name);
                h.put(name.toLowerCase(), v != null ? Integer.toString(v.length()) : "0");
            }
        }
        return h.hashCode();
    }

    @Override
    public int metricsEndpointHistoryEntryCount() {
        return endpointHistory.size();
    }

    private int extractIpBucket(HttpServletRequest request) {
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
