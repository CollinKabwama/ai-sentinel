package io.aisentinel.autoconfigure.distributed;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when distributed mode, cluster quarantine read, and Redis-backed reader are all enabled via properties.
 */
public final class OnDistributedRedisQuarantineEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        boolean sentinel = env.getProperty("ai.sentinel.enabled", Boolean.class, true);
        if (!sentinel) {
            return false;
        }
        boolean dist = env.getProperty("ai.sentinel.distributed.enabled", Boolean.class, false);
        boolean clusterRead = env.getProperty("ai.sentinel.distributed.cluster-quarantine-read-enabled", Boolean.class, false);
        boolean redis = env.getProperty("ai.sentinel.distributed.redis.enabled", Boolean.class, false);
        return dist && clusterRead && redis;
    }
}
