package io.aisentinel.autoconfigure.config;

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
}
