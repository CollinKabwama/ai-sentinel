package io.aisentinel.autoconfigure.model;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * True when {@code ai.sentinel.model-registry.filesystem-root} is non-blank.
 */
final class OnModelRegistryFilesystemRootCondition implements Condition {

    static final String PROPERTY = "ai.sentinel.model-registry.filesystem-root";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String v = context.getEnvironment().getProperty(PROPERTY, "");
        return StringUtils.hasText(v);
    }
}
