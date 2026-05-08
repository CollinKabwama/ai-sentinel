package io.aisentinel.core.identity.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityRiskSignalsTest {

    @Test
    void emptyFactory() {
        assertThat(IdentityRiskSignals.empty().components()).isEmpty();
    }

    @Test
    void nullComponentsBecomeEmptyMap() {
        assertThat(new IdentityRiskSignals(null).components()).isEmpty();
    }

    @Test
    void componentsAreCopiedAndImmutable() {
        Map<String, Double> m = new HashMap<>();
        m.put("k", 1.0);
        IdentityRiskSignals s = new IdentityRiskSignals(m);
        m.put("k2", 2.0);
        assertThat(s.components()).containsExactly(Map.entry("k", 1.0));
    }
}
