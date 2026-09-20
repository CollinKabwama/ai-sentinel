package dev.aisentinel.core.scoring.lifecycle;

import dev.aisentinel.distributed.training.TrainingFingerprintHashes;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic lifecycle canonicalization helpers.
 */
final class ModelLifecycleCanonical {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern SHA_256_HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    private ModelLifecycleCanonical() {
    }

    static String requireLifecycleToken(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        String trimmed = value.trim();
        if (!TOKEN_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(name + " has invalid format");
        }
        return trimmed;
    }

    static String requireSha256Hex(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        String trimmed = value.trim();
        if (!SHA_256_HEX_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(name + " must be 64-char SHA-256 hex");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    static void appendField(StringBuilder sb, String name, String value) {
        String safeValue = value == null ? "" : value;
        sb.append(name.length()).append(':').append(name)
            .append('=')
            .append(safeValue.length()).append(':').append(safeValue)
            .append('\n');
    }

    static String sha256Hex(String material) {
        return TrainingFingerprintHashes.sha256HexUtf8(material);
    }
}
