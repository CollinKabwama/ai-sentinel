package io.aisentinel.core.metrics;

import io.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Ensures {@link SentinelMetrics#NOOP} remains safe to call for every hook on the interface
 * (regression guard for new default methods).
 */
class SentinelMetricsNoopTest {

    @Test
    void noopSingletonIsStable() {
        assertThat(SentinelMetrics.NOOP).isSameAs(SentinelMetrics.NOOP);
    }

    @Test
    void noopImplementsInterface() {
        assertThat(SentinelMetrics.NOOP).isInstanceOf(SentinelMetrics.class);
    }

    @Test
    void allDefaultMetricHooksCallableOnNoop() {
        SentinelMetrics metrics = SentinelMetrics.NOOP;
        assertThatCode(() -> {
            for (Method method : SentinelMetrics.class.getDeclaredMethods()) {
                if (!method.isDefault()) {
                    continue;
                }
                Object[] args = new Object[method.getParameterCount()];
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < types.length; i++) {
                    args[i] = sampleArg(types[i]);
                }
                method.invoke(metrics, args);
            }
        }).doesNotThrowAnyException();
    }

    private static Object sampleArg(Class<?> type) {
        if (type == double.class) {
            return 0.42;
        }
        if (type == long.class) {
            return 99L;
        }
        if (type == EnforcementAction.class) {
            return EnforcementAction.ALLOW;
        }
        throw new IllegalArgumentException("Update SentinelMetricsNoopTest for new parameter type: " + type);
    }
}
