package io.aisentinel.core.identity.trust;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityBehavioralBaselineStoreTest {

    @Test
    void expiredEntriesArePrunedBeforeUpdate() {
        IdentityBehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMillis(30), 10_000);
        assertThat(store.updateAndGetPrevious("k1", "/a", 0L, 0, 0L)).isNull();
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.updateAndGetPrevious("k1", "/a", 0L, 0, 10_000L)).isNull();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void respectsMaxKeysByEvictingOldest() {
        IdentityBehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofHours(1), 3);
        long t = 5_000_000L;
        store.updateAndGetPrevious("a", "/", 0L, 0, t);
        store.updateAndGetPrevious("b", "/", 0L, 0, t + 1);
        store.updateAndGetPrevious("c", "/", 0L, 0, t + 2);
        assertThat(store.size()).isEqualTo(3);
        store.updateAndGetPrevious("d", "/", 0L, 0, t + 3);
        assertThat(store.size()).isEqualTo(3);
    }
}
