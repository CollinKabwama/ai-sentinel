package dev.aisentinel.core.store;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

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

    @Test
    void rollingCountPersistsAcrossTenSecondBucketBoundary() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 1000, now::get);

        assertThat(store.incrementAndGet("id|/api")).isEqualTo(1);
        assertThat(store.incrementAndGet("id|/api")).isEqualTo(2);

        // Just before bucket boundary
        long bucketEnd = ((now.get() / BaselineStore.bucketMs()) + 1) * BaselineStore.bucketMs();
        now.set(bucketEnd - 1);
        assertThat(store.incrementAndGet("id|/api")).isEqualTo(3);

        // Across boundary into the next 10s bucket — count must not reset
        now.set(bucketEnd);
        assertThat(store.incrementAndGet("id|/api")).isEqualTo(4);
        now.set(bucketEnd + 1);
        assertThat(store.get("id|/api")).isEqualTo(4);
    }

    @Test
    void rollingCountDropsOnlyAfterTtlAgesOutBuckets() {
        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        Duration ttl = Duration.ofSeconds(30);
        BaselineStore store = new BaselineStore(ttl, 1000, now::get);

        assertThat(store.incrementAndGet("id|/api")).isEqualTo(1);
        now.addAndGet(10_000);
        assertThat(store.incrementAndGet("id|/api")).isEqualTo(2);
        now.addAndGet(10_000);
        assertThat(store.incrementAndGet("id|/api")).isEqualTo(3);

        // Advance past TTL from the first bucket; older buckets age out of the rolling sum.
        now.addAndGet(ttl.toMillis() + BaselineStore.bucketMs());
        int afterAge = store.get("id|/api");
        assertThat(afterAge).isLessThan(3);
    }
}
