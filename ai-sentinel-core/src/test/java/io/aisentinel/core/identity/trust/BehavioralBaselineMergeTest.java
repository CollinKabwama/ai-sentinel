package io.aisentinel.core.identity.trust;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BehavioralBaselineMergeTest {

    @Test
    void mergeIncrementsFromNullPrevious() {
        BehavioralBaselineEntry n = BehavioralBaselineMerge.merge(null, "/a", 1L, 2, 100L);
        assertThat(n.observationCount).isEqualTo(1);
        assertThat(n.lastEndpoint).isEqualTo("/a");
        assertThat(n.lastSeenMs).isEqualTo(100L);
    }

    @Test
    void mergeIncrementsFromExisting() {
        BehavioralBaselineEntry old = new BehavioralBaselineEntry();
        old.observationCount = 3;
        old.lastSeenMs = 50L;
        BehavioralBaselineEntry n = BehavioralBaselineMerge.merge(old, "/b", 2L, 3, 200L);
        assertThat(n.observationCount).isEqualTo(4);
        assertThat(n.lastEndpoint).isEqualTo("/b");
    }
}
