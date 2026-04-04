package io.aisentinel.autoconfigure.distributed;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * True when distributed + Redis flags are on and at least one of cluster quarantine read or write is enabled.
 * Used to load {@link DistributedQuarantineAutoConfiguration} so reader and/or writer beans can register.
 */
public final class OnDistributedRedisQuarantineClientEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        if (!env.getProperty("ai.sentinel.enabled", Boolean.class, true)) {
            return false;
        }
        boolean dist = env.getProperty("ai.sentinel.distributed.enabled", Boolean.class, false);
        boolean redis = env.getProperty("ai.sentinel.distributed.redis.enabled", Boolean.class, false);
        boolean read = env.getProperty("ai.sentinel.distributed.cluster-quarantine-read-enabled", Boolean.class, false);
        boolean write = env.getProperty("ai.sentinel.distributed.cluster-quarantine-write-enabled", Boolean.class, false);
        return dist && redis && (read || write);
    }
}
