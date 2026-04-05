package io.aisentinel.autoconfigure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.scoring.IsolationForestScorer;
import io.aisentinel.model.ModelRegistryReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.nio.file.Path;

/**
 * Optional filesystem model registry for Phase 5.6. Disabled unless refresh and root path are configured.
 */
@AutoConfiguration(after = SentinelAutoConfiguration.class)
@ConditionalOnProperty(name = "ai.sentinel.enabled", havingValue = "true", matchIfMissing = true)
public class ModelRegistryAutoConfiguration {

    @Bean
    @Conditional(OnModelRegistryFilesystemRootCondition.class)
    @ConditionalOnExpression("'${ai.sentinel.model-registry.refresh-enabled:false}'=='true' && '${ai.sentinel.isolation-forest.enabled:false}'=='true'")
    @ConditionalOnBean({IsolationForestScorer.class, SentinelProperties.class, SentinelMetrics.class})
    public ModelRegistryReader filesystemModelRegistry(SentinelProperties props,
                                                       ObjectProvider<ObjectMapper> objectMapperProvider) {
        String root = props.getModelRegistry().getFilesystemRoot().trim();
        ObjectMapper om = objectMapperProvider.getIfAvailable();
        return new FilesystemModelRegistry(Path.of(root), om);
    }

    @Bean
    @Conditional(OnModelRegistryFilesystemRootCondition.class)
    @ConditionalOnExpression("'${ai.sentinel.model-registry.refresh-enabled:false}'=='true' && '${ai.sentinel.isolation-forest.enabled:false}'=='true'")
    @ConditionalOnBean({IsolationForestScorer.class, ModelRegistryReader.class, SentinelMetrics.class})
    public ModelRefreshScheduler modelRefreshScheduler(IsolationForestScorer isolationForestScorer,
                                                       ModelRegistryReader modelRegistryReader,
                                                       SentinelProperties props,
                                                       SentinelMetrics sentinelMetrics) {
        return new ModelRefreshScheduler(isolationForestScorer, modelRegistryReader, props, sentinelMetrics);
    }
}
