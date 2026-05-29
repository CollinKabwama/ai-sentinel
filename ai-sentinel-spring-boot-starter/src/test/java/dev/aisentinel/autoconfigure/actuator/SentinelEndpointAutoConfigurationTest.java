package dev.aisentinel.autoconfigure.actuator;

import dev.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import dev.aisentinel.autoconfigure.identity.IdentityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelEndpointAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            IdentityAutoConfiguration.class,
            SentinelAutoConfiguration.class,
            SentinelEndpointAutoConfiguration.class));

    @Test
    void registersSentinelActuatorEndpointWhenPipelinePresent() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(SentinelActuatorEndpoint.class));
    }
}
