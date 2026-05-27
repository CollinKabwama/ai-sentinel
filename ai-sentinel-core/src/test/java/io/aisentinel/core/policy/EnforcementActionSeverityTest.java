package io.aisentinel.core.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the assumption used by {@link DefaultTrustPolicyAdjuster}: escalation merges by enum declaration order.
 */
class EnforcementActionSeverityTest {

    @Test
    void enforcementActionsAreDeclaredInAscendingSeverityOrder() {
        EnforcementAction[] values = EnforcementAction.values();
        assertThat(values).containsExactly(
            EnforcementAction.ALLOW,
            EnforcementAction.MONITOR,
            EnforcementAction.THROTTLE,
            EnforcementAction.BLOCK,
            EnforcementAction.QUARANTINE
        );
        for (int i = 1; i < values.length; i++) {
            assertThat(values[i].ordinal()).isGreaterThan(values[i - 1].ordinal());
        }
    }
}
