package io.aisentinel.core.enforcement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnforcementKeysTest {

    @Test
    void identityEndpointJoinsWithPipe() {
        assertThat(EnforcementKeys.enforcementKey(EnforcementScope.IDENTITY_ENDPOINT, "a", "/x"))
            .isEqualTo("a|/x");
    }

    @Test
    void identityGlobalUsesHashOnly() {
        assertThat(EnforcementKeys.enforcementKey(EnforcementScope.IDENTITY_GLOBAL, "a", "/x"))
            .isEqualTo("a");
    }
}
