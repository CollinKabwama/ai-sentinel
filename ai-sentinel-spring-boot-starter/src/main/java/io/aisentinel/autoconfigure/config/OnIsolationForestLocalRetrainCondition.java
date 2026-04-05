package io.aisentinel.autoconfigure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enables {@link IsolationForestRetrainScheduler} when {@code ai.sentinel.isolation-forest.local-retrain-enabled}
 * is true (default) and a registry refresh poller would not also be registered (same gate as filesystem registry beans:
 * {@code model-registry.refresh-enabled} with a non-empty {@code model-registry.filesystem-root}).
 */
public final class OnIsolationForestLocalRetrainCondition implements Condition {

    private static final Logger log = LoggerFactory.getLogger(OnIsolationForestLocalRetrainCondition.class);
    private static final AtomicBoolean SUPPRESS_WARN_ONCE = new AtomicBoolean();

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean localRetrain = context.getEnvironment()
            .getProperty("ai.sentinel.isolation-forest.local-retrain-enabled", Boolean.class, Boolean.TRUE);
        if (!localRetrain) {
            return false;
        }
        boolean refreshEnabled = context.getEnvironment()
            .getProperty("ai.sentinel.model-registry.refresh-enabled", Boolean.class, Boolean.FALSE);
        String root = context.getEnvironment().getProperty("ai.sentinel.model-registry.filesystem-root", "");
        boolean registryRefreshWired = refreshEnabled && StringUtils.hasText(root.trim());
        if (registryRefreshWired) {
            if (SUPPRESS_WARN_ONCE.compareAndSet(false, true)) {
                log.warn(
                    "Isolation Forest local retrain is suppressed because model registry refresh is enabled with a "
                        + "non-empty filesystem-root; only registry rollout should mutate the active model. "
                        + "Set ai.sentinel.isolation-forest.local-retrain-enabled=false to silence this message.");
            }
            return false;
        }
        return true;
    }
}
