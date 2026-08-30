package dev.aisentinel.autoconfigure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Default and explicit operating-mode binding.
 */
class DefaultOperatingModeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(SentinelProperties.class)
    static class TestConfig {
    }

    @Test
    void unspecifiedModeDefaultsToMonitor() {
        contextRunner.run(context -> {
            SentinelProperties props = context.getBean(SentinelProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getMode()).isEqualTo(SentinelProperties.Mode.MONITOR);
        });
    }

    @Test
    void explicitMonitorRemainsMonitor() {
        contextRunner
            .withPropertyValues("ai.sentinel.mode=MONITOR")
            .run(context -> assertThat(context.getBean(SentinelProperties.class).getMode())
                .isEqualTo(SentinelProperties.Mode.MONITOR));
    }

    @Test
    void explicitEnforceRemainsEnforce() {
        contextRunner
            .withPropertyValues("ai.sentinel.mode=ENFORCE")
            .run(context -> assertThat(context.getBean(SentinelProperties.class).getMode())
                .isEqualTo(SentinelProperties.Mode.ENFORCE));
    }

    @Test
    void explicitOffRemainsOff() {
        contextRunner
            .withPropertyValues("ai.sentinel.mode=OFF")
            .run(context -> assertThat(context.getBean(SentinelProperties.class).getMode())
                .isEqualTo(SentinelProperties.Mode.OFF));
    }
}
