package dev.aisentinel.core.pilot;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Pilot-scoped HMAC-SHA256 pseudonymization of the existing pipeline identity key.
 * <p>
 * The secret must never be written to pilot evidence artifacts or logs.
 */
public final class PilotPseudonymizer {

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();
    private static final int MIN_SECRET_UTF8_BYTES = 16;

    private final byte[] secretBytes;

    public PilotPseudonymizer(String secret) {
        Objects.requireNonNull(secret, "secret");
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_UTF8_BYTES) {
            throw new IllegalArgumentException(
                "pilot pseudonymization secret must be at least " + MIN_SECRET_UTF8_BYTES + " UTF-8 bytes");
        }
        this.secretBytes = bytes.clone();
    }

    public String pseudonymize(String pipelineIdentityKey) {
        Objects.requireNonNull(pipelineIdentityKey, "pipelineIdentityKey");
        return hmacHex("identity:" + pipelineIdentityKey);
    }

    /**
     * Pilot-scoped endpoint pseudonym. Keeps endpoint grouping stable within a pilot
     * without persisting raw/high-cardinality path segments.
     */
    public String pseudonymizeEndpoint(String endpointKey) {
        Objects.requireNonNull(endpointKey, "endpointKey");
        return hmacHex("endpoint:" + endpointKey);
    }

    private String hmacHex(String material) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
            byte[] digest = mac.doFinal(material.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 pseudonymization failed", e);
        }
    }
}
