package io.aisentinel.autoconfigure.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class IdentityHashing {

    private IdentityHashing() {}

    static String sha256Hex(String raw) {
        if (raw == null) {
            raw = "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 required by JRE spec", e);
        }
    }
}
