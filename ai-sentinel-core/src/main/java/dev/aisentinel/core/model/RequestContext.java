package dev.aisentinel.core.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-request context shared between feature extraction and scoring.
 * Holds rolling state (e.g. endpoint history for entropy) and request metadata.
 */
public final class RequestContext {

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = attributes.get(key);
        return v != null ? (T) v : null;
    }
}
