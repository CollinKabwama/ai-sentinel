package io.aisentinel.autoconfigure.distributed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedQuarantineLookupCacheTest {

    @Test
    void missThenPositiveHit() {
        var cache = new BoundedQuarantineLookupCache(100, 60_000L, 1000L);
        long now = 1_000_000L;
        assertThat(cache.lookup("k", now)).isEmpty();

        cache.putPositive("k", now + 10_000, now);
        assertThat(cache.lookup("k", now).orElseThrow().orElseThrow()).isEqualTo(now + 10_000);
    }

    @Test
    void negativeCacheHit() {
        var cache = new BoundedQuarantineLookupCache(100, 60_000L, 1000L);
        long now = 2_000_000L;
        cache.putNegative("k", now);
        assertThat(cache.lookup("k", now).orElseThrow()).isEmpty();
    }

    @Test
    void expiresAfterTtl() {
        var cache = new BoundedQuarantineLookupCache(100, 100L, 50L);
        long t0 = 5_000_000L;
        cache.putPositive("k", t0 + 50_000, t0);
        assertThat(cache.lookup("k", t0 + 200).isEmpty()).isTrue();
    }

    @Test
    void negativeEntryExpiresAfterNegativeTtl() {
        var cache = new BoundedQuarantineLookupCache(100, 60_000L, 50L);
        long t0 = 1_000_000L;
        cache.putNegative("k", t0);
        assertThat(cache.lookup("k", t0 + 51)).isEmpty();
    }

    @Test
    void evictsWhenOverCapacity() {
        var cache = new BoundedQuarantineLookupCache(3, 60_000L, 1000L);
        long now = 0L;
        cache.putNegative("a", now);
        cache.putNegative("b", now);
        cache.putNegative("c", now);
        cache.putNegative("d", now);
        assertThat(cache.size()).isLessThanOrEqualTo(3);
    }
}
