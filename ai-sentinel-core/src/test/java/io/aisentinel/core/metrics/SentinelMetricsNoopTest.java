package io.aisentinel.core.metrics;

import io.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage for {@link SentinelMetrics#NOOP}: every default hook must be safe to call from hot paths.
 */
class SentinelMetricsNoopTest {

    @Test
    void noopSingletonIsStable() {
        assertThat(SentinelMetrics.NOOP).isSameAs(SentinelMetrics.NOOP);
    }

    @Test
    void invokingAllDefaultMethodsOnNoopDoesNotThrow() throws Exception {
        SentinelMetrics m = SentinelMetrics.NOOP;
        for (Method method : SentinelMetrics.class.getDeclaredMethods()) {
            if (!method.isDefault()) {
                continue;
            }
            Object[] args = argsFor(method.getParameterTypes());
            method.invoke(m, args);
        }
    }

    private static Object[] argsFor(Class<?>[] paramTypes) {
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> t = paramTypes[i];
            if (t == double.class) {
                args[i] = 0.42;
            } else if (t == long.class) {
                args[i] = 99L;
            } else if (t == EnforcementAction.class) {
                args[i] = EnforcementAction.ALLOW;
            } else {
                throw new IllegalStateException("Unexpected parameter type for SentinelMetrics default: " + t);
            }
        }
        return args;
    }
}
