package dev.aisentinel.core.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityContextKeysTest {

    @Test
    void identityContextKeyIsStable() {
        assertThat(IdentityContextKeys.IDENTITY_CONTEXT).isEqualTo("dev.aisentinel.identity.context");
    }
}
