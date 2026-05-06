package io.aisentinel.autoconfigure.config;

import io.aisentinel.core.runtime.StartupGrace;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelStartupGraceTest {

    @Test
    void zeroOrNegativeGraceNeverActive() {
        assertThat(new SentinelStartupGrace(Duration.ZERO).isGraceActive()).isFalse();
        assertThat(new SentinelStartupGrace(Duration.ofSeconds(-1)).isGraceActive()).isFalse();
    }

    @Test
    void positiveGraceActiveInitially() {
        StartupGrace grace = new SentinelStartupGrace(Duration.ofHours(1));
        assertThat(grace.isGraceActive()).isTrue();
    }

    @Test
    void nullGraceTreatedAsZero() {
        assertThat(new SentinelStartupGrace(null).isGraceActive()).isFalse();
    }
}
