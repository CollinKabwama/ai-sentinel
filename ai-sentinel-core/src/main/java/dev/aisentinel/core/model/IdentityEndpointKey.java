package dev.aisentinel.core.model;

import java.util.Objects;

/**
 * Structured identity|endpoint key for hot-path maps. Avoids repeated string concatenation on every
 * lookup while preserving the canonical {@code identityHash + '|' + endpoint} storage shape where
 * required for enforcement and distributed backends.
 */
public final class IdentityEndpointKey {

    private enum Kind {
        ENDPOINT_SCOPED,
        IDENTITY_SCOPED,
        OPAQUE
    }

    private final Kind kind;
    private final String identityHash;
    private final String endpoint;
    private final String opaqueKey;

    private IdentityEndpointKey(Kind kind, String identityHash, String endpoint, String opaqueKey) {
        this.kind = kind;
        this.identityHash = identityHash;
        this.endpoint = endpoint;
        this.opaqueKey = opaqueKey;
    }

    /** Per-endpoint scorer/baseline key ({@code identityHash + '|' + endpoint}). */
    public static IdentityEndpointKey forEndpoint(String identityHash, String endpoint) {
        return new IdentityEndpointKey(
            Kind.ENDPOINT_SCOPED,
            Objects.requireNonNull(identityHash, "identityHash"),
            endpoint != null ? endpoint : "",
            null
        );
    }

    /** Identity-global enforcement key ({@code identityHash} only). */
    public static IdentityEndpointKey forIdentity(String identityHash) {
        return new IdentityEndpointKey(
            Kind.IDENTITY_SCOPED,
            Objects.requireNonNull(identityHash, "identityHash"),
            "",
            null
        );
    }

    /** Opaque storage key (legacy string map entries without structured parsing). */
    static IdentityEndpointKey opaque(String storageKey) {
        return new IdentityEndpointKey(
            Kind.OPAQUE,
            null,
            null,
            Objects.requireNonNull(storageKey, "storageKey")
        );
    }

    /**
     * Parses canonical {@code identity|endpoint} keys; arbitrary strings without {@code '|'} remain opaque.
     */
    public static IdentityEndpointKey fromStorageKey(String storageKey) {
        Objects.requireNonNull(storageKey, "storageKey");
        int pipe = storageKey.indexOf('|');
        if (pipe >= 0) {
            return forEndpoint(storageKey.substring(0, pipe), storageKey.substring(pipe + 1));
        }
        return opaque(storageKey);
    }

    public String identityHash() {
        return kind == Kind.OPAQUE ? "" : identityHash;
    }

    public String endpoint() {
        return kind == Kind.ENDPOINT_SCOPED ? endpoint : "";
    }

    /** Canonical wire/storage representation for Redis and enforcement backends. */
    public String storageKey() {
        return switch (kind) {
            case ENDPOINT_SCOPED -> identityHash + "|" + endpoint;
            case IDENTITY_SCOPED -> identityHash;
            case OPAQUE -> opaqueKey;
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IdentityEndpointKey that)) {
            return false;
        }
        return kind == that.kind
            && Objects.equals(identityHash, that.identityHash)
            && Objects.equals(endpoint, that.endpoint)
            && Objects.equals(opaqueKey, that.opaqueKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, identityHash, endpoint, opaqueKey);
    }

    @Override
    public String toString() {
        return storageKey();
    }
}
