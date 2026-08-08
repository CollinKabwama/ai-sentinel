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
        assertThat(p.getStatistical().getRelearnMode())
            .isEqualTo(dev.aisentinel.core.baseline.BaselineRelearnMode.DISABLED);
        assertThat(validator.validate(p)).isEmpty();
    }

    @Test
    void acceptsExplicitOnlyRelearnMode() {
        SentinelProperties p = new SentinelProperties();
        p.getStatistical().setRelearnMode(dev.aisentinel.core.baseline.BaselineRelearnMode.EXPLICIT_ONLY);
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

    @Test
    void defaultInternalMapMaxKeysIsAccepted() {
        SentinelProperties p = new SentinelProperties();
        assertThat(p.getInternalMapMaxKeys()).isEqualTo(100_000);
        assertThat(validator.validate(p)).isEmpty();
    }

    @Test
    void rejectsInternalMapMaxKeysZero() {
        SentinelProperties p = new SentinelProperties();
        p.setInternalMapMaxKeys(0);
        assertThat(validator.validate(p)).isNotEmpty();
    }

    @Test
    void rejectsInternalMapMaxKeysNegative() {
        SentinelProperties p = new SentinelProperties();
        p.setInternalMapMaxKeys(-1);
        assertThat(validator.validate(p)).isNotEmpty();
    }

    @Test
    void rejectsInternalMapMaxKeysAbsurdlySmall() {
        SentinelProperties p = new SentinelProperties();
        p.setInternalMapMaxKeys(999);
        assertThat(validator.validate(p)).isNotEmpty();
    }

    @Test
    void acceptsInternalMapMaxKeysAtMinimumBound() {
        SentinelProperties p = new SentinelProperties();
        p.setInternalMapMaxKeys(1_000);
        assertThat(validator.validate(p)).isEmpty();
    }

    @Test
    void rejectsInternalMapMaxKeysAboveMaximum() {
        SentinelProperties p = new SentinelProperties();
        p.setInternalMapMaxKeys(2_000_001);
        assertThat(validator.validate(p)).isNotEmpty();
    }
}
