package dev.aisentinel.autoconfigure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aisentinel.autoconfigure.web.RemoteEvaluationController;
import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.contract.EvaluationExecutor;
import dev.aisentinel.core.contract.LocalEvaluationBridge;
import dev.aisentinel.core.contract.LocalEvaluationExecutor;
import dev.aisentinel.core.metrics.SentinelMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Wires local/remote {@link EvaluationExecutor} and optional authenticated evaluation endpoint.
 * Local remains the default; remote client beans are created only for remote executor modes.
 */
@AutoConfiguration(after = dev.aisentinel.autoconfigure.config.SentinelAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "ai.sentinel.enabled", havingValue = "true", matchIfMissing = true)
public class EvaluationAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EvaluationAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public LocalEvaluationBridge localEvaluationBridge(SentinelPipeline pipeline) {
        return new LocalEvaluationBridge(
            pipeline.featureExtractor(),
            pipeline.decisionEngine(),
            pipeline.identityContextResolver());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnRemoteExecutorMode
    public RemoteEvaluationClient remoteEvaluationClient(SentinelProperties props,
                                                         ObjectMapper objectMapper,
                                                         ObjectProvider<SentinelMetrics> metricsProvider) {
        SentinelMetrics metrics = metricsProvider.getIfAvailable();
        if (metrics == null) {
            metrics = SentinelMetrics.NOOP;
        }
        return buildClient(props, objectMapper, metrics);
    }

    /**
     * Single application-facing executor. Local by default; remote modes only when configured.
     * Custom {@link EvaluationExecutor} beans replace this entirely.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(EvaluationExecutor.class)
    public EvaluationExecutor evaluationExecutor(SentinelProperties props,
                                                 LocalEvaluationBridge localEvaluationBridge,
                                                 ObjectProvider<RemoteEvaluationClient> remoteClientProvider,
                                                 ObjectProvider<SentinelMetrics> metricsProvider) {
        LocalEvaluationExecutor local = new LocalEvaluationExecutor(localEvaluationBridge);
        SentinelMetrics metrics = metricsProvider.getIfAvailable();
        if (metrics == null) {
            metrics = SentinelMetrics.NOOP;
        }
        SentinelProperties.Evaluation.ExecutorMode mode = props.getEvaluation().getExecutorMode();
        if (mode == SentinelProperties.Evaluation.ExecutorMode.REMOTE) {
            RemoteEvaluationClient client = remoteClientProvider.getIfAvailable();
            if (client == null) {
                throw new IllegalStateException("REMOTE executor mode requires RemoteEvaluationClient");
            }
            log.info("EvaluationExecutor mode=REMOTE");
            return new RemoteEvaluationExecutor(client);
        }
        if (mode == SentinelProperties.Evaluation.ExecutorMode.REMOTE_WITH_LOCAL_FALLBACK) {
            RemoteEvaluationClient client = remoteClientProvider.getIfAvailable();
            if (client == null) {
                throw new IllegalStateException(
                    "REMOTE_WITH_LOCAL_FALLBACK requires RemoteEvaluationClient");
            }
            log.info("EvaluationExecutor mode=REMOTE_WITH_LOCAL_FALLBACK");
            return new RemoteWithLocalFallbackExecutor(
                new RemoteEvaluationExecutor(client), local, metrics);
        }
        log.debug("EvaluationExecutor mode=LOCAL");
        return local;
    }

    @Bean
    @ConditionalOnProperty(name = "ai.sentinel.evaluation.server.enabled", havingValue = "true")
    @ConditionalOnMissingBean(RemoteEvaluationController.class)
    public RemoteEvaluationController remoteEvaluationController(
        LocalEvaluationBridge localEvaluationBridge,
        SentinelProperties props) {
        // Server always evaluates locally against this JVM's authoritative engine/state.
        log.info("Remote evaluation endpoint enabled at POST {}", RemoteEvaluationController.PATH);
        return new RemoteEvaluationController(
            new LocalEvaluationExecutor(localEvaluationBridge), props);
    }

    private static RemoteEvaluationClient buildClient(SentinelProperties props,
                                                      ObjectMapper objectMapper,
                                                      SentinelMetrics metrics) {
        SentinelProperties.Evaluation.Client client = props.getEvaluation().getClient();
        return new RemoteEvaluationClient(
            client.getBaseUrl(),
            client.getPath(),
            client.getApiKey(),
            client.getConnectTimeout(),
            client.getReadTimeout(),
            objectMapper,
            metrics);
    }
}
