package io.aisentinel.core.identity.trust;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class BehavioralBaselineStoreTest {

    @Test
    void identityStoreIsBehavioralBaselineStore() {
        BehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMinutes(5), 100);
        assertThat(store).isInstanceOf(BehavioralBaselineStore.class);
    }

    @Test
    void updateAndGetPreviousReturnsNullOnFirstWrite() {
        BehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMinutes(5), 100);
        BehavioralBaselineEntry prev = store.updateAndGetPrevious("k1", "/a", 1L, 2, 10_000L);
        assertThat(prev).isNull();
    }

    @Test
    void updateAndGetPreviousReturnsPriorSnapshotOnSecondWrite() {
        BehavioralBaselineStore store = new IdentityBehavioralBaselineStore(Duration.ofMinutes(5), 100);
        long t0 = 1_000_000L;
        assertThat(store.updateAndGetPrevious("k1", "/first", 10L, 1, t0)).isNull();

        BehavioralBaselineEntry prev = store.updateAndGetPrevious("k1", "/second", 20L, 2, t0 + 5_000L);
        assertThat(prev).isNotNull();
        assertThat(prev.lastEndpoint).isEqualTo("/first");
        assertThat(prev.lastHeaderFingerprintHash).isEqualTo(10L);
        assertThat(prev.lastIpBucket).isEqualTo(1);
    }
}
