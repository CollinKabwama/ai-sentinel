package io.aisentinel.autoconfigure.distributed;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Status bean is needed when either cluster quarantine read or write integration is enabled.
 */
public final class OnDistributedQuarantineStatusNeededCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        if (!env.getProperty("ai.sentinel.enabled", Boolean.class, true)) {
            return false;
        }
        boolean read = env.getProperty("ai.sentinel.distributed.cluster-quarantine-read-enabled", Boolean.class, false);
        boolean write = env.getProperty("ai.sentinel.distributed.cluster-quarantine-write-enabled", Boolean.class, false);
        return read || write;
    }
}
