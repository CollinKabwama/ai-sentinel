package io.aisentinel.autoconfigure.distributed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedThrottleKeyBuilderTest {

    @Test
    void redisKeyIncludesTenantBucketAndEnforcementKey() {
        String k = DistributedThrottleKeyBuilder.redisKey("pfx", "t1", 42L, "ab|/api");
        assertThat(k).isEqualTo("pfx:t1:th:42:ab|/api");
    }
}
