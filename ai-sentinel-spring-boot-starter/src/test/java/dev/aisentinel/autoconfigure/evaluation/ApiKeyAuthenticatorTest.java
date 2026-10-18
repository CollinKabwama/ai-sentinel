package dev.aisentinel.autoconfigure.evaluation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthenticatorTest {

    @Test
    void matchingKeysSucceed() {
        assertThat(ApiKeyAuthenticator.matches("test-eval-key-12345678", "test-eval-key-12345678")).isTrue();
    }

    @Test
    void mismatchedKeysFail() {
        assertThat(ApiKeyAuthenticator.matches("test-eval-key-12345678", "wrong-key")).isFalse();
    }

    @Test
    void blankOrNullNeverSucceed() {
        assertThat(ApiKeyAuthenticator.matches("test-eval-key-12345678", null)).isFalse();
        assertThat(ApiKeyAuthenticator.matches("test-eval-key-12345678", "")).isFalse();
        assertThat(ApiKeyAuthenticator.matches("test-eval-key-12345678", "   ")).isFalse();
        assertThat(ApiKeyAuthenticator.matches(null, "test-eval-key-12345678")).isFalse();
        assertThat(ApiKeyAuthenticator.matches("", "test-eval-key-12345678")).isFalse();
        assertThat(ApiKeyAuthenticator.matches("   ", "   ")).isFalse();
    }

    @Test
    void whitespaceIsNotSilentlyTrimmed() {
        assertThat(ApiKeyAuthenticator.matches("test-eval-key-12345678", " test-eval-key-12345678 ")).isFalse();
    }
}
