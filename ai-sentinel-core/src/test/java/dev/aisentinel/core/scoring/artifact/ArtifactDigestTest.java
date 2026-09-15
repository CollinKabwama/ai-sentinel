package dev.aisentinel.core.scoring.artifact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArtifactDigestTest {

    private static final String VALID =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void acceptsLowercaseSha256AndNormalizesUppercase() {
        ArtifactDigest lower = ArtifactDigest.sha256Hex(VALID);
        ArtifactDigest upper = ArtifactDigest.sha256Hex(VALID.toUpperCase());
        assertThat(lower.digestHex()).isEqualTo(VALID);
        assertThat(upper.digestHex()).isEqualTo(VALID);
        assertThat(lower.algorithm()).isEqualTo(ArtifactDigestAlgorithm.SHA_256);
    }

    @Test
    void rejectsWrongLength() {
        assertThatThrownBy(() -> ArtifactDigest.sha256Hex("abcd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("length");
    }

    @Test
    void rejectsNonHex() {
        String bad = "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertThatThrownBy(() -> ArtifactDigest.sha256Hex(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hexadecimal");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> ArtifactDigest.sha256Hex(" "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLeadingOrTrailingWhitespace() {
        assertThatThrownBy(() -> ArtifactDigest.sha256Hex(" " + VALID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("whitespace");
        assertThatThrownBy(() -> ArtifactDigest.sha256Hex(VALID + "\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("whitespace");
    }

    @Test
    void parseAlgorithmAcceptsWireName() {
        assertThat(ArtifactDigestAlgorithm.parse("SHA-256")).isEqualTo(ArtifactDigestAlgorithm.SHA_256);
        assertThatThrownBy(() -> ArtifactDigestAlgorithm.parse("md5"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
