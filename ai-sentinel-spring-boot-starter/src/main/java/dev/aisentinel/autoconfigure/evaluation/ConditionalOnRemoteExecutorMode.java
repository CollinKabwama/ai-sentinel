package dev.aisentinel.autoconfigure.evaluation;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Matches when {@code ai.sentinel.evaluation.executor-mode} is a remote mode.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ConditionalOnRemoteExecutorMode.OnRemoteExecutorModeCondition.class)
public @interface ConditionalOnRemoteExecutorMode {

    class OnRemoteExecutorModeCondition extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String raw = context.getEnvironment().getProperty(
                "ai.sentinel.evaluation.executor-mode", "LOCAL");
            try {
                SentinelProperties.Evaluation.ExecutorMode mode =
                    SentinelProperties.Evaluation.ExecutorMode.valueOf(raw.trim().toUpperCase());
                if (mode == SentinelProperties.Evaluation.ExecutorMode.LOCAL) {
                    return ConditionOutcome.noMatch("executor-mode is LOCAL");
                }
                return ConditionOutcome.match("executor-mode is " + mode);
            } catch (IllegalArgumentException ex) {
                return ConditionOutcome.noMatch("unknown executor-mode: " + raw);
            }
        }
    }
}
