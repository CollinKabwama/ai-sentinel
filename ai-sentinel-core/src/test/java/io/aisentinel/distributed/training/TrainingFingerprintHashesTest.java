package io.aisentinel.distributed.training;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingFingerprintHashesTest {

    @Test
    void sha256HexIsStableAndLowercase() {
        String a = TrainingFingerprintHashes.sha256HexUtf8("/users/123/orders");
        String b = TrainingFingerprintHashes.sha256HexUtf8("/users/123/orders");
        assertThat(a).hasSize(64).isEqualTo(b).matches("[0-9a-f]{64}");
    }

    @Test
    void differentInputsDiffer() {
        assertThat(TrainingFingerprintHashes.sha256HexUtf8("/a"))
            .isNotEqualTo(TrainingFingerprintHashes.sha256HexUtf8("/b"));
    }

    @Test
    void nullTreatedAsEmptyString() {
        assertThat(TrainingFingerprintHashes.sha256HexUtf8(null))
            .isEqualTo(TrainingFingerprintHashes.sha256HexUtf8(""));
    }
}
