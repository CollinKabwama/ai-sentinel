package io.aisentinel.core.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrustPolicyContextKeysTest {

    @Test
    void trustPolicyDetailKeyIsStable() {
        assertThat(TrustPolicyContextKeys.TRUST_POLICY_DETAIL)
            .isEqualTo("io.aisentinel.trustPolicy.detail")
            .doesNotContain(" ");
    }
}
