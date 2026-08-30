package dev.aisentinel.core.model;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFeaturesEffectiveTimestampTest {

    @Test
    void effectiveTimestampUsesCapturedMillisWhenPositive() {
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h")
            .endpoint("/e")
            .timestampMillis(1_700_000_000_000L)
            .build();
        assertThat(features.effectiveTimestampMillis()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void effectiveTimestampFallsBackToSystemTimeWhenUnset() {
        long before = System.currentTimeMillis();
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h")
            .endpoint("/e")
            .timestampMillis(0L)
            .build();
        long after = System.currentTimeMillis();
        assertThat(features.effectiveTimestampMillis()).isBetween(before, after);
    }

    @Test
    void effectiveTimestampFallbackIsStableAfterConstruction() {
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h")
            .endpoint("/e")
            .timestampMillis(0L)
            .fallbackClock(clock::get)
            .build();

        long first = features.effectiveTimestampMillis();
        clock.addAndGet(5_000L);

        assertThat(features.effectiveTimestampMillis()).isEqualTo(first);
        assertThat(features.timestampMillis()).isZero();
    }
}
