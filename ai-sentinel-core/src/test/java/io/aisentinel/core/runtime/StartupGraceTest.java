package io.aisentinel.core.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StartupGraceTest {

    @Test
    void neverGraceIsAlwaysInactive() {
        assertThat(StartupGrace.NEVER.isGraceActive()).isFalse();
    }
}
