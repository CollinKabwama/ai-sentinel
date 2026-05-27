package io.aisentinel.distributed.quarantine;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class NoopClusterQuarantineReaderTest {

    @Test
    void instanceAlwaysEmpty() {
        OptionalLong until = NoopClusterQuarantineReader.INSTANCE.quarantineUntil("t", "k");
        assertThat(until).isEmpty();
    }
}
