package io.aisentinel.core.store;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BaselineStoreTest {

    @Test
    void incrementAndGetCountsRequests() {
        var store = new BaselineStore(Duration.ofMinutes(1), 1000);
        assertThat(store.incrementAndGet("k1")).isEqualTo(1);
        assertThat(store.incrementAndGet("k1")).isEqualTo(2);
        assertThat(store.incrementAndGet("k2")).isEqualTo(1);
        assertThat(store.get("k1")).isEqualTo(2);
    }

    @Test
    void evictsWhenOverMaxKeys() {
        var store = new BaselineStore(Duration.ofMinutes(5), 3);
        for (int i = 0; i < 5; i++) {
            store.incrementAndGet("k" + i);
        }
        int evicted = 0;
        String keptKey = null;
        for (int i = 0; i < 5; i++) {
            if (store.get("k" + i) == 0) evicted++;
            else keptKey = "k" + i;
        }
        assertThat(evicted).isGreaterThanOrEqualTo(2);
        assertThat(keptKey).isNotNull();
        for (int i = 0; i < 9; i++) {
            store.incrementAndGet(keptKey);
        }
        assertThat(store.get(keptKey)).isEqualTo(10);
    }
}
