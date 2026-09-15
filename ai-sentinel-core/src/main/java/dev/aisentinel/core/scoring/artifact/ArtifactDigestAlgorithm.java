package dev.aisentinel.core.scoring.artifact;

/**
 * Supported artifact integrity digest algorithms for candidate scorer/model metadata.
 * <p>
 * The algorithm is explicit so an unqualified hex string is never treated as
 * universally meaningful. This contract validates digest metadata only; matching
 * digest bytes to loaded artifact content belongs to later loading work.
 */
public enum ArtifactDigestAlgorithm {
    SHA_256("SHA-256", 64);

    private final String wireName;
    private final int hexLength;

    ArtifactDigestAlgorithm(String wireName, int hexLength) {
        this.wireName = wireName;
        this.hexLength = hexLength;
    }

    public String wireName() {
        return wireName;
    }

    public int hexLength() {
        return hexLength;
    }

    public static ArtifactDigestAlgorithm parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("digest algorithm must not be blank");
        }
        String normalized = value.trim();
        for (ArtifactDigestAlgorithm algorithm : values()) {
            if (algorithm.wireName.equalsIgnoreCase(normalized)
                || algorithm.name().equalsIgnoreCase(normalized)
                || algorithm.name().replace('_', '-').equalsIgnoreCase(normalized)) {
                return algorithm;
            }
        }
        throw new IllegalArgumentException("Unsupported digest algorithm: " + value);
    }
}
