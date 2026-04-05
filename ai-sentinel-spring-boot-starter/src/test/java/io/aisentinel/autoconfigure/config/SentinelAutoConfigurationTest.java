package io.aisentinel.autoconfigure.config;

import io.aisentinel.autoconfigure.distributed.DistributedQuarantineAutoConfiguration;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatusAutoConfiguration;
import io.aisentinel.autoconfigure.distributed.DistributedThrottleStatusAutoConfiguration;
import io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader;
import io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineWriter;
import io.aisentinel.autoconfigure.distributed.throttle.RedisClusterThrottleStore;
import io.aisentinel.autoconfigure.web.SentinelFilter;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.policy.PolicyEngine;
import io.aisentinel.core.policy.ThresholdPolicyEngine;
import io.aisentinel.distributed.quarantine.ClusterQuarantineReader;
import io.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import io.aisentinel.distributed.quarantine.NoopClusterQuarantineReader;
import io.aisentinel.autoconfigure.distributed.training.AsyncTrainingCandidatePublisher;
import io.aisentinel.autoconfigure.distributed.training.TrainingPublishStatus;
import io.aisentinel.distributed.training.NoopTrainingCandidatePublisher;
import io.aisentinel.distributed.training.TrainingCandidatePublisher;
import io.aisentinel.distributed.throttle.ClusterThrottleStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void registersRedisClusterQuarantineReaderWhenDistributedRedisFlagsAndTemplate() {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DistributedQuarantineStatusAutoConfiguration.class,
                DistributedQuarantineAutoConfiguration.class,
                SentinelAutoConfiguration.class))
            .withUserConfiguration(RedisTemplateStub.class)
            .withPropertyValues(
                "ai.sentinel.enabled=true",
                "ai.sentinel.distributed.enabled=true",
                "ai.sentinel.distributed.cluster-quarantine-read-enabled=true",
                "ai.sentinel.distributed.redis.enabled=true")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(ClusterQuarantineReader.class);
                assertThat(ctx.getBean(ClusterQuarantineReader.class)).isInstanceOf(RedisClusterQuarantineReader.class);
            });
    }

    @Test
    void noopClusterReaderWhenClusterReadButRedisDisabled() {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DistributedQuarantineAutoConfiguration.class, SentinelAutoConfiguration.class))
            .withPropertyValues(
                "ai.sentinel.enabled=true",
                "ai.sentinel.distributed.enabled=true",
                "ai.sentinel.distributed.cluster-quarantine-read-enabled=true",
                "ai.sentinel.distributed.redis.enabled=false")
            .run(ctx -> assertThat(ctx.getBean(ClusterQuarantineReader.class)).isSameAs(NoopClusterQuarantineReader.INSTANCE));
    }

    @Test
    void noClusterReaderBeanWhenClusterReadDisabled() {
        contextRunner
            .withPropertyValues("ai.sentinel.enabled=true", "ai.sentinel.distributed.cluster-quarantine-read-enabled=false")
            .run(ctx -> assertThat(ctx.getBeansOfType(ClusterQuarantineReader.class)).isEmpty());
    }

    @Test
    void registersRedisClusterQuarantineWriterWhenWriteFlagsAndTemplate() {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DistributedQuarantineStatusAutoConfiguration.class,
                DistributedQuarantineAutoConfiguration.class,
                SentinelAutoConfiguration.class))
            .withUserConfiguration(RedisTemplateStub.class)
            .withPropertyValues(
                "ai.sentinel.enabled=true",
                "ai.sentinel.distributed.enabled=true",
                "ai.sentinel.distributed.cluster-quarantine-read-enabled=false",
                "ai.sentinel.distributed.cluster-quarantine-write-enabled=true",
                "ai.sentinel.distributed.redis.enabled=true")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(ClusterQuarantineWriter.class);
                assertThat(ctx.getBean(ClusterQuarantineWriter.class)).isInstanceOf(RedisClusterQuarantineWriter.class);
            });
    }

    @Test
    void noClusterQuarantineWriterBeanWhenWriteDisabled() {
        contextRunner
            .withPropertyValues(
                "ai.sentinel.enabled=true",
                "ai.sentinel.distributed.enabled=true",
                "ai.sentinel.distributed.cluster-quarantine-write-enabled=false",
                "ai.sentinel.distributed.redis.enabled=true")
            .run(ctx -> assertThat(ctx.getBeansOfType(ClusterQuarantineWriter.class)).isEmpty());
    }

    @Test
    void trainingPublishUsesNoopWhenDisabled() {
        contextRunner
            .withPropertyValues("ai.sentinel.enabled=true")
            .run(ctx -> assertThat(ctx.getBean(TrainingCandidatePublisher.class))
                .isSameAs(NoopTrainingCandidatePublisher.INSTANCE));
    }

    @Test
    void trainingPublishUsesAsyncPublisherWhenEnabled() {
        contextRunner
            .withPropertyValues(
                "ai.sentinel.enabled=true",
                "ai.sentinel.distributed.training-publish-enabled=true")
            .run(ctx -> {
                assertThat(ctx.getBean(TrainingCandidatePublisher.class)).isInstanceOf(AsyncTrainingCandidatePublisher.class);
                assertThat(ctx).hasSingleBean(TrainingPublishStatus.class);
            });
    }

    @Test
    void registersRedisClusterThrottleStoreWhenThrottleOnlyAndTemplate() {
        new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                DistributedThrottleStatusAutoConfiguration.class,
                DistributedQuarantineAutoConfiguration.class,
                SentinelAutoConfiguration.class))
            .withUserConfiguration(ClusterThrottleRedisTemplateStub.class)
            .withPropertyValues(
                "ai.sentinel.enabled=true",
                "ai.sentinel.distributed.enabled=true",
                "ai.sentinel.distributed.cluster-quarantine-read-enabled=false",
                "ai.sentinel.distributed.cluster-quarantine-write-enabled=false",
                "ai.sentinel.distributed.cluster-throttle-enabled=true",
                "ai.sentinel.distributed.redis.enabled=true")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(ClusterThrottleStore.class);
                assertThat(ctx.getBean(ClusterThrottleStore.class)).isInstanceOf(RedisClusterThrottleStore.class);
            });
    }

    @Configuration
    static class RedisTemplateStub {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            @SuppressWarnings("unchecked")
            ValueOperations<String, String> ops = mock(ValueOperations.class);
            when(ops.get(anyString())).thenReturn(null);
            return new StringRedisTemplate() {
                @Override
                public void afterPropertiesSet() {
                    // Skip RedisConnectionFactory requirement in slice tests
                }

                @Override
                public ValueOperations<String, String> opsForValue() {
                    return ops;
                }
            };
        }
    }

    @Configuration
    static class ClusterThrottleRedisTemplateStub {
        @Bean
        StringRedisTemplate stringRedisTemplate() {
            return new StringRedisTemplate() {
                @Override
                public void afterPropertiesSet() {
                }

                @Override
                @SuppressWarnings("unchecked")
                public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                    return (T) Long.valueOf(1L);
                }
            };
        }
    }
}
