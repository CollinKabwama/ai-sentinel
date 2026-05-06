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
}
