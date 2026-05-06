package io.aisentinel.autoconfigure.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnIsolationForestLocalRetrainConditionTest {

    @Test
    void suppressedWhenRegistryRefreshWiredWithFilesystemRoot() {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new MapPropertySource("t", Map.of(
            "ai.sentinel.isolation-forest.local-retrain-enabled", "true",
            "ai.sentinel.model-registry.refresh-enabled", "true",
            "ai.sentinel.model-registry.filesystem-root", "/tmp/registry")));
        ConditionContext ctx = mock(ConditionContext.class);
        when(ctx.getEnvironment()).thenReturn(env);
        assertThat(new OnIsolationForestLocalRetrainCondition().matches(ctx, mock(AnnotatedTypeMetadata.class)))
            .isFalse();
    }

    @Test
    void allowedWhenNoRegistryRefreshOrEmptyRoot() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t", Map.of(
            "ai.sentinel.isolation-forest.local-retrain-enabled", "true",
            "ai.sentinel.model-registry.refresh-enabled", "true",
            "ai.sentinel.model-registry.filesystem-root", "  ")));
        ConditionContext ctx = mock(ConditionContext.class);
        when(ctx.getEnvironment()).thenReturn(env);
        assertThat(new OnIsolationForestLocalRetrainCondition().matches(ctx, mock(AnnotatedTypeMetadata.class)))
            .isTrue();
    }

    @Test
    void disabledWhenLocalRetrainPropertyFalse() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t", Map.of(
            "ai.sentinel.isolation-forest.local-retrain-enabled", "false")));
        ConditionContext ctx = mock(ConditionContext.class);
        when(ctx.getEnvironment()).thenReturn(env);
        assertThat(new OnIsolationForestLocalRetrainCondition().matches(ctx, mock(AnnotatedTypeMetadata.class)))
            .isFalse();
    }
}
