package dev.aisentinel.core.enforcement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class DiscardingEnforcementResponseTest {

    @Test
    void writesAreNoOps() {
        EnforcementResponse response = DiscardingEnforcementResponse.INSTANCE;
        assertThatCode(() -> {
            response.setStatus(429);
            response.setContentType("text/plain");
            response.writeBody("ignored");
        }).doesNotThrowAnyException();
    }
}
