package io.aisentinel.autoconfigure.distributed;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when distributed mode, cluster throttle, and Redis are enabled.
 */
public final class OnDistributedClusterThrottleEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        if (!env.getProperty("ai.sentinel.enabled", Boolean.class, true)) {
            return false;
        }
        boolean dist = env.getProperty("ai.sentinel.distributed.enabled", Boolean.class, false);
        boolean redis = env.getProperty("ai.sentinel.distributed.redis.enabled", Boolean.class, false);
        boolean throttle = env.getProperty("ai.sentinel.distributed.cluster-throttle-enabled", Boolean.class, false);
        return dist && redis && throttle;
    }
}
