package io.aisentinel.core.enforcement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EnforcementScopeTest {

    @Test
    void bothEnumValuesPresent() {
        assertThat(EnforcementScope.values()).containsExactlyInAnyOrder(
            EnforcementScope.IDENTITY_GLOBAL,
            EnforcementScope.IDENTITY_ENDPOINT
        );
    }
}
