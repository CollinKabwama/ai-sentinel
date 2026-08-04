package dev.aisentinel.core.policy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ThresholdPolicyEngine#evaluate} in complete isolation: a scalar score in, an action out, with no features,
 * no HTTP request, no scorer, and no framework. Complements {@link ThresholdPolicyEngineTest}.
 */
class PolicyIsolationTest {

    private final PolicyEngine policy = new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8);

    @Test
    void mapsScoreBandsToActionsWithoutAnyCollaborators() {
        assertThat(policy.evaluate(0.0, null, "/x")).isEqualTo(EnforcementAction.ALLOW);
        assertThat(policy.evaluate(0.2, null, "/x")).isEqualTo(EnforcementAction.MONITOR);
        assertThat(policy.evaluate(0.4, null, "/x")).isEqualTo(EnforcementAction.THROTTLE);
        assertThat(policy.evaluate(0.6, null, "/x")).isEqualTo(EnforcementAction.BLOCK);
        assertThat(policy.evaluate(0.8, null, "/x")).isEqualTo(EnforcementAction.QUARANTINE);
    }

    @Test
    void evaluateIsPureForRepeatedCalls() {
        for (int i = 0; i < 3; i++) {
            assertThat(policy.evaluate(0.55, null, "/repeat")).isEqualTo(EnforcementAction.THROTTLE);
        }
    }
}
