package io.aisentinel.autoconfigure.distributed;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-matrix checks for distributed {@link Condition} implementations (no Spring context startup).
 */
class DistributedOnConditionPropertiesTest {

    @Test
    void onDistributedRedisQuarantineEnabled_requiresAllFlags() {
        Condition c = new OnDistributedRedisQuarantineEnabledCondition();
        assertThat(matches(c, Map.of())).isFalse();
        assertThat(matches(c, props(
            "ai.sentinel.enabled", "true",
            "ai.sentinel.distributed.enabled", "true",
            "ai.sentinel.distributed.cluster-quarantine-read-enabled", "true",
            "ai.sentinel.distributed.redis.enabled", "true"))).isTrue();
    }

    @Test
    void onDistributedClusterThrottleEnabled_requiresThrottleFlag() {
        Condition c = new OnDistributedClusterThrottleEnabledCondition();
        assertThat(matches(c, props(
            "ai.sentinel.enabled", "true",
            "ai.sentinel.distributed.enabled", "true",
            "ai.sentinel.distributed.redis.enabled", "true",
            "ai.sentinel.distributed.cluster-throttle-enabled", "false"))).isFalse();
        assertThat(matches(c, props(
            "ai.sentinel.enabled", "true",
            "ai.sentinel.distributed.enabled", "true",
            "ai.sentinel.distributed.redis.enabled", "true",
            "ai.sentinel.distributed.cluster-throttle-enabled", "true"))).isTrue();
    }

    @Test
    void onDistributedQuarantineStatusNeeded_whenReadOrWrite() {
        Condition c = new OnDistributedQuarantineStatusNeededCondition();
        assertThat(matches(c, props(
            "ai.sentinel.enabled", "true",
            "ai.sentinel.distributed.cluster-quarantine-read-enabled", "true"))).isTrue();
        assertThat(matches(c, props(
            "ai.sentinel.enabled", "true",
            "ai.sentinel.distributed.cluster-quarantine-write-enabled", "true"))).isTrue();
        assertThat(matches(c, props(
            "ai.sentinel.enabled", "true",
            "ai.sentinel.distributed.cluster-quarantine-read-enabled", "false",
            "ai.sentinel.distributed.cluster-quarantine-write-enabled", "false"))).isFalse();
    }

    @Test
    void onDistributedRedisQuarantineClientEnabled_anyOfReadWriteThrottle() {
        Condition c = new OnDistributedRedisQuarantineClientEnabledCondition();
        assertThat(matches(c, props(
            "ai.sentinel.enabled", "true",
            "ai.sentinel.distributed.enabled", "true",
            "ai.sentinel.distributed.redis.enabled", "true",
            "ai.sentinel.distributed.cluster-throttle-enabled", "true"))).isTrue();
        assertThat(matches(c, props(
            "ai.sentinel.enabled", "true",
            "ai.sentinel.distributed.enabled", "true",
            "ai.sentinel.distributed.redis.enabled", "true",
            "ai.sentinel.distributed.cluster-quarantine-read-enabled", "false",
            "ai.sentinel.distributed.cluster-quarantine-write-enabled", "false",
            "ai.sentinel.distributed.cluster-throttle-enabled", "false"))).isFalse();
    }

    @Test
    void onDistributedRedisQuarantineWriteEnabled() {
        Condition c = new OnDistributedRedisQuarantineWriteEnabledCondition();
        assertThat(matches(c, props(
            "ai.sentinel.enabled", "true",
            "ai.sentinel.distributed.enabled", "true",
            "ai.sentinel.distributed.cluster-quarantine-write-enabled", "true",
            "ai.sentinel.distributed.redis.enabled", "true"))).isTrue();
    }

    @Test
    void onDistributedThrottleStatusNeeded() {
        Condition c = new OnDistributedThrottleStatusNeededCondition();
        assertThat(matches(c, props(
            "ai.sentinel.enabled", "true",
            "ai.sentinel.distributed.enabled", "true",
            "ai.sentinel.distributed.cluster-throttle-enabled", "true"))).isTrue();
    }

    private static Map<String, Object> props(String... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static boolean matches(Condition condition, Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new MapPropertySource("test", props));
        ConditionContext ctx = mock(ConditionContext.class);
        when(ctx.getEnvironment()).thenReturn(env);
        AnnotatedTypeMetadata meta = mock(AnnotatedTypeMetadata.class);
        return condition.matches(ctx, meta);
    }
}
