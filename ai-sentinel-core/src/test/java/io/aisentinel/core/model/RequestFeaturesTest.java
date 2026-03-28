package io.aisentinel.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFeaturesTest {

    @Test
    void toArrayReturnsCorrectOrder() {
        var f = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(5)
            .endpointEntropy(1.2)
            .tokenAgeSeconds(60)
            .parameterCount(3)
            .payloadSizeBytes(100)
            .headerFingerprintHash(42)
            .ipBucket(123)
            .build();

        double[] a = f.toArray();
        assertThat(a).hasSize(7);
        assertThat(a[0]).isEqualTo(5);
        assertThat(a[1]).isEqualTo(1.2);
        assertThat(a[2]).isEqualTo(60);
        assertThat(a[3]).isEqualTo(3);
        assertThat(a[4]).isEqualTo(100);
        assertThat(a[5]).isEqualTo(42);
        assertThat(a[6]).isEqualTo(123);
    }

    @Test
    void toIsolationForestArrayExcludesHashFeatures() {
        var f = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api")
            .timestampMillis(0)
            .requestsPerWindow(5)
            .endpointEntropy(1.2)
            .tokenAgeSeconds(60)
            .parameterCount(3)
            .payloadSizeBytes(100)
            .headerFingerprintHash(42)
            .ipBucket(123)
            .build();

        double[] ifVec = f.toIsolationForestArray();
        assertThat(ifVec).hasSize(5);
        assertThat(ifVec[0]).isEqualTo(5);
        assertThat(ifVec[1]).isEqualTo(1.2);
        assertThat(ifVec[2]).isEqualTo(60);
        assertThat(ifVec[3]).isEqualTo(3);
        assertThat(ifVec[4]).isEqualTo(100);
    }
}
