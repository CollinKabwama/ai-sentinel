package dev.aisentinel.autoconfigure.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SentinelPropertiesValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setup() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void rejectsClusterThrottleWindowUnder100ms() {
        SentinelProperties p = new SentinelProperties();
        p.getDistributed().setClusterThrottleWindow(Duration.ofMillis(50));
        assertThat(validator.validate(p)).isNotEmpty();
    }

    @Test
    void rejectsClusterThrottleMaxRequestsBelowOne() {
        SentinelProperties p = new SentinelProperties();
        p.getDistributed().setClusterThrottleMaxRequestsPerWindow(0);
        assertThat(validator.validate(p)).isNotEmpty();
    }

    @Test
    void rejectsTrainingPublishSampleRateAboveOne() {
        SentinelProperties p = new SentinelProperties();
        p.getDistributed().setTrainingPublishSampleRate(1.01);
        assertThat(validator.validate(p)).isNotEmpty();
    }

    @Test
    void rejectsTrainingPublishTimeoutAboveThirtySeconds() {
        SentinelProperties p = new SentinelProperties();
        p.getDistributed().setTrainingPublishTimeout(Duration.ofSeconds(31));
        assertThat(validator.validate(p)).isNotEmpty();
    }

    @Test
    void rejectsWarmupActionThrottle() {
        SentinelProperties p = new SentinelProperties();
        p.setWarmupAction(dev.aisentinel.core.policy.EnforcementAction.THROTTLE);
        assertThat(validator.validate(p)).isNotEmpty();
    }

    @Test
    void defaultBaselineUpdatePolicyIsAllowOrMonitor() {
        SentinelProperties p = new SentinelProperties();
        assertThat(p.getStatistical().getBaselineUpdatePolicy())
            .isEqualTo(dev.aisentinel.core.baseline.BaselineUpdateMode.ALLOW_OR_MONITOR);
        assertThat(p.getStatistical().getBaselineUpdateScoreThreshold()).isEqualTo(0.4);
        assertThat(validator.validate(p)).isEmpty();
    }

    @Test
    void rejectsBaselineUpdateScoreThresholdOutOfRange() {
        SentinelProperties p = new SentinelProperties();
        p.getStatistical().setBaselineUpdateScoreThreshold(1.5);
        assertThat(validator.validate(p)).isNotEmpty();
    }

    @Test
    void acceptsAlwaysBaselineUpdatePolicy() {
        SentinelProperties p = new SentinelProperties();
        p.getStatistical().setBaselineUpdatePolicy(dev.aisentinel.core.baseline.BaselineUpdateMode.ALWAYS);
        assertThat(validator.validate(p)).isEmpty();
    }
}
