package dev.aisentinel.autoconfigure.evaluation;

import dev.aisentinel.autoconfigure.web.RemoteEvaluationController;

import dev.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import dev.aisentinel.core.contract.EvaluationExecutor;
import dev.aisentinel.core.contract.EvaluationFailureResponses;
import dev.aisentinel.core.contract.LocalEvaluationExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            JacksonAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            SentinelAutoConfiguration.class,
            EvaluationAutoConfiguration.class));

    @Test
    void localDefaultCreatesLocalExecutorWithoutRemoteClient() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(EvaluationExecutor.class);
            assertThat(context.getBean(EvaluationExecutor.class)).isInstanceOf(LocalEvaluationExecutor.class);
            assertThat(context).doesNotHaveBean(RemoteEvaluationClient.class);
            assertThat(context).doesNotHaveBean(RemoteEvaluationController.class);
        });
    }

    @Test
    void remoteServerEnabledRegistersController() {
        runner.withPropertyValues(
                "ai.sentinel.evaluation.server.enabled=true",
                "ai.sentinel.evaluation.server.api-key=server-secret-key")
            .run(context -> {
                assertThat(context).hasSingleBean(RemoteEvaluationController.class);
                assertThat(context).hasSingleBean(EvaluationExecutor.class);
            });
    }

    @Test
    void remoteModeRequiresClientConfiguration() {
        runner.withPropertyValues(
                "ai.sentinel.evaluation.executor-mode=REMOTE",
                "ai.sentinel.evaluation.client.base-url=https://eval.example.com",
                "ai.sentinel.evaluation.client.api-key=client-secret-key",
                "ai.sentinel.evaluation.client.require-https=true")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(RemoteEvaluationClient.class);
                assertThat(context.getBean(EvaluationExecutor.class))
                    .isInstanceOf(RemoteEvaluationExecutor.class);
            });
    }

    @Test
    void customEvaluationExecutorBeanIsRespected() {
        runner.withBean(EvaluationExecutor.class,
                () -> request -> EvaluationFailureResponses.remoteFailure(request.correlationId()))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(EvaluationExecutor.class);
                assertThat(context.getBean(EvaluationExecutor.class))
                    .isNotInstanceOf(LocalEvaluationExecutor.class);
            });
    }
}
