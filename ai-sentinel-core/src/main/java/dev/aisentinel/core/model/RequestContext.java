package dev.aisentinel.core.model;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable per-request bag shared across identity resolution, feature extraction, and decision evaluation.
 * <p>
 * Holds rolling feature state (for example endpoint history for entropy) and optional identity / fusion /
 * trust-policy detail keys. One instance is created per request and must not be shared across threads.
 */
public final class RequestContext {

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /** Stores or replaces an attribute under {@code key}. */
    public void put(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Returns the attribute for {@code key} cast to {@code type}, or {@code null} if absent.
     * Callers are responsible for using consistent key/type pairs.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = attributes.get(key);
        return v != null ? (T) v : null;
    }
}
