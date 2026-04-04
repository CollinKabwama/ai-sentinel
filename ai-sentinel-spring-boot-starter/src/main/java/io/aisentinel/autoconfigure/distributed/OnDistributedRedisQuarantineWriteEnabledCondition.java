package io.aisentinel.autoconfigure.distributed;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when distributed mode, cluster quarantine write propagation, and Redis are enabled.
 */
public final class OnDistributedRedisQuarantineWriteEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        if (!env.getProperty("ai.sentinel.enabled", Boolean.class, true)) {
            return false;
        }
        boolean dist = env.getProperty("ai.sentinel.distributed.enabled", Boolean.class, false);
        boolean write = env.getProperty("ai.sentinel.distributed.cluster-quarantine-write-enabled", Boolean.class, false);
        boolean redis = env.getProperty("ai.sentinel.distributed.redis.enabled", Boolean.class, false);
        return dist && write && redis;
    }
}
