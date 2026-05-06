package io.aisentinel.distributed.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedSubsystemStatusTest {

    @Test
    void allStatusesPresent() {
        assertThat(DistributedSubsystemStatus.values())
            .containsExactly(
                DistributedSubsystemStatus.NOT_CONFIGURED,
                DistributedSubsystemStatus.OK,
                DistributedSubsystemStatus.DEGRADED,
                DistributedSubsystemStatus.UNAVAILABLE);
    }
}
