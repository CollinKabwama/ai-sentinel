package io.aisentinel.autoconfigure.distributed.quarantine;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RedisClusterQuarantineReaderNegativeTtlTest {

    @Test
    void explicitNegativeTtlOverridesDerived() {
        assertThat(RedisClusterQuarantineReader.resolveNegativeTtlMillis(Duration.ofMillis(300), 10_000)).isEqualTo(300);
    }

    @Test
    void nullNegativeTtlDerivesFromPositive() {
        assertThat(RedisClusterQuarantineReader.resolveNegativeTtlMillis(null, 500)).isEqualTo(250);
    }
}
