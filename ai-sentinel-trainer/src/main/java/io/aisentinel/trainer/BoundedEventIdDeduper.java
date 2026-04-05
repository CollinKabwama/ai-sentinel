package io.aisentinel.trainer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded LRU of recent {@code eventId} values for at-least-once delivery. Capacity 0 disables deduplication.
 */
final class BoundedEventIdDeduper {

    private final int capacity;
    private final LinkedHashMap<String, Boolean> map;

    BoundedEventIdDeduper(int capacity) {
        this.capacity = Math.max(0, capacity);
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return BoundedEventIdDeduper.this.capacity > 0 && size() > BoundedEventIdDeduper.this.capacity;
            }
        };
    }

    synchronized boolean firstTime(String eventId) {
        if (capacity <= 0 || eventId == null || eventId.isBlank()) {
            return true;
        }
        if (map.containsKey(eventId)) {
            return false;
        }
        map.put(eventId, Boolean.TRUE);
        return true;
    }
}
