package dev.aisentinel.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityEndpointKeyTest {

    @Test
    void endpointScopedStorageKeyUsesPipeSeparator() {
        IdentityEndpointKey key = IdentityEndpointKey.forEndpoint("abc12345", "/api/users");
        assertThat(key.storageKey()).isEqualTo("abc12345|/api/users");
    }

    @Test
    void identityScopedStorageKeyOmitsEndpoint() {
        IdentityEndpointKey key = IdentityEndpointKey.forIdentity("abc12345");
        assertThat(key.storageKey()).isEqualTo("abc12345");
    }

    @Test
    void fromStorageKeyParsesCanonicalShape() {
        IdentityEndpointKey key = IdentityEndpointKey.fromStorageKey("hash|/path");
        assertThat(key.identityHash()).isEqualTo("hash");
        assertThat(key.endpoint()).isEqualTo("/path");
        assertThat(key.storageKey()).isEqualTo("hash|/path");
    }

    @Test
    void opaqueStorageKeyPreservesArbitraryString() {
        IdentityEndpointKey key = IdentityEndpointKey.fromStorageKey("k1");
        assertThat(key.storageKey()).isEqualTo("k1");
    }

    @Test
    void equalKeysShareHashCodeForMapLookup() {
        IdentityEndpointKey a = IdentityEndpointKey.forEndpoint("id", "/e");
        IdentityEndpointKey b = IdentityEndpointKey.forEndpoint("id", "/e");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void requestFeaturesExposesStableIdentityEndpointKey() {
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/e")
            .timestampMillis(1L)
            .build();
        assertThat(features.identityEndpointKey()).isEqualTo(IdentityEndpointKey.forEndpoint("id", "/e"));
        assertThat(features.identityEndpointKey()).isSameAs(features.identityEndpointKey());
    }

    @Test
    void structuredEqualityAvoidsDelimiterCollisionInMemory() {
        IdentityEndpointKey first = IdentityEndpointKey.forEndpoint("id|with-pipe", "/api");
        IdentityEndpointKey second = IdentityEndpointKey.forEndpoint("id", "with-pipe|/api");

        assertThat(first.storageKey()).isEqualTo(second.storageKey());
        assertThat(first).isNotEqualTo(second);
    }
}
