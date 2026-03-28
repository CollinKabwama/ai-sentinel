package io.aisentinel.autoconfigure.config;

import io.aisentinel.autoconfigure.web.SentinelFilter;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.policy.PolicyEngine;
import io.aisentinel.core.policy.ThresholdPolicyEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SentinelAutoConfiguration.class));

    private static RequestFeatures dummyFeatures() {
        return RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();
    }

    @Test
    void autoConfigurationCreatesSentinelBeansWhenEnabled() {
        contextRunner
            .withPropertyValues("ai.sentinel.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(io.aisentinel.core.SentinelPipeline.class);
                assertThat(context).hasSingleBean(SentinelFilter.class);
            });
    }

    @Test
    void propertiesAreAvailable() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(SentinelProperties.class));
    }

    @Test
    void invalidPolicyThresholdOrderingFailsStartup() {
        contextRunner
            .withPropertyValues(
                "ai.sentinel.enabled=true",
                "ai.sentinel.threshold-moderate=0.3",
                "ai.sentinel.threshold-elevated=0.2",
                "ai.sentinel.threshold-high=0.6",
                "ai.sentinel.threshold-critical=0.8")
            .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void customPolicyThresholdsBindAndDrivePolicyEngine() {
        contextRunner
            .withPropertyValues(
                "ai.sentinel.enabled=true",
                "ai.sentinel.threshold-moderate=0.1",
                "ai.sentinel.threshold-elevated=0.3",
                "ai.sentinel.threshold-high=0.5",
                "ai.sentinel.threshold-critical=0.7")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                PolicyEngine policy = ctx.getBean(PolicyEngine.class);
                assertThat(policy).isInstanceOf(ThresholdPolicyEngine.class);
                var f = dummyFeatures();
                assertThat(policy.evaluate(0.05, f, "/api")).isEqualTo(EnforcementAction.ALLOW);
                assertThat(policy.evaluate(0.15, f, "/api")).isEqualTo(EnforcementAction.MONITOR);
                assertThat(policy.evaluate(0.35, f, "/api")).isEqualTo(EnforcementAction.THROTTLE);
                assertThat(policy.evaluate(0.55, f, "/api")).isEqualTo(EnforcementAction.BLOCK);
                assertThat(policy.evaluate(0.75, f, "/api")).isEqualTo(EnforcementAction.QUARANTINE);
            });
    }
}
