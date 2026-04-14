package io.aisentinel.autoconfigure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(SentinelPropertiesTest.TestConfig.class);

    @EnableConfigurationProperties(SentinelProperties.class)
    static class TestConfig { }

    @Test
    void defaultsAreApplied() {
        contextRunner.run(context -> {
            SentinelProperties props = context.getBean(SentinelProperties.class);
            assertThat(props.isEnabled()).isTrue();
            assertThat(props.getMode()).isEqualTo(SentinelProperties.Mode.ENFORCE);
            assertThat(props.getBlockStatusCode()).isEqualTo(429);
            assertThat(props.getBaselineTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(props.getIsolationForest().isEnabled()).isFalse();
            assertThat(props.getDistributed().isClusterQuarantineWriteEnabled()).isFalse();
            assertThat(props.getIdentity().isEnabled()).isFalse();
        });
    }

    @Test
    void customPropertiesAreBound() {
        contextRunner
            .withPropertyValues(
                "ai.sentinel.enabled=false",
                "ai.sentinel.mode=MONITOR",
                "ai.sentinel.block-status-code=403",
                "ai.sentinel.isolation-forest.enabled=true",
                "ai.sentinel.distributed.cluster-quarantine-write-enabled=true"
            )
            .run(context -> {
                SentinelProperties props = context.getBean(SentinelProperties.class);
                assertThat(props.isEnabled()).isFalse();
                assertThat(props.getMode()).isEqualTo(SentinelProperties.Mode.MONITOR);
                assertThat(props.getBlockStatusCode()).isEqualTo(403);
                assertThat(props.getIsolationForest().isEnabled()).isTrue();
                assertThat(props.getDistributed().isClusterQuarantineWriteEnabled()).isTrue();
            });
    }
}
