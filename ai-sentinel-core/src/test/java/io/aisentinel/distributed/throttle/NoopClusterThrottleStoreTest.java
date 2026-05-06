package io.aisentinel.distributed.throttle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoopClusterThrottleStoreTest {

    @Test
    void tryAcquireAlwaysTrue() {
        assertThat(NoopClusterThrottleStore.INSTANCE.tryAcquire("any", "key")).isTrue();
    }
}
