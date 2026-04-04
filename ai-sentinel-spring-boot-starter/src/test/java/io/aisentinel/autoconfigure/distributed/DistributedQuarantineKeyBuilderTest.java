package io.aisentinel.autoconfigure.distributed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DistributedQuarantineKeyBuilderTest {

    @Test
    void buildsTenantScopedKey() {
        assertThat(DistributedQuarantineKeyBuilder.redisKey("aisentinel", "acme", "id|/api"))
            .isEqualTo("aisentinel:acme:q:id|/api");
    }

    @Test
    void defaultsBlankTenantToDefault() {
        assertThat(DistributedQuarantineKeyBuilder.redisKey("p", "  ", "k"))
            .isEqualTo("p:default:q:k");
    }

    @Test
    void defaultsBlankPrefix() {
        assertThat(DistributedQuarantineKeyBuilder.redisKey("  ", "t", "k"))
            .isEqualTo("aisentinel:t:q:k");
    }
}
