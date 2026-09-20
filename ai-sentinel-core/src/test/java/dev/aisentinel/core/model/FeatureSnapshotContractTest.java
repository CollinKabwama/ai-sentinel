package dev.aisentinel.core.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureSnapshotContractTest {

    @Test
    void snapshotDerivedFromRequestFeaturesPreservesCanonicalValuesAndProjectionOrder() {
        RequestFeatures requestFeatures = RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api/orders")
            .timestampMillis(1L)
            .requestsPerWindow(2.0)
            .endpointEntropy(0.1)
            .endpointConcentration(0.7)
            .tokenAgeSeconds(3.0)
            .parameterCount(4)
            .payloadSizeBytes(5L)
            .headerFingerprintHash(6L)
            .ipBucket(7)
            .build();

        FeatureSnapshot snapshot = requestFeatures.toFeatureSnapshot();

        assertThat(snapshot).isEqualTo(new FeatureSnapshot(2.0, 0.1, 0.7, 3.0, 4, 5L, 6L, 7));
        assertThat(snapshot.schemaVersion()).isEqualTo(FeatureSchema.VERSION_ID);
        assertThat(snapshot.project(FeatureProjectionId.STATISTICAL))
            .containsExactly(requestFeatures.toStatisticalArray());
        assertThat(snapshot.project(FeatureProjectionId.ISOLATION_FOREST))
            .containsExactly(requestFeatures.toIsolationForestArray());
        assertThat(snapshot.project(FeatureProjectionId.EXPORT))
            .containsExactly(requestFeatures.toArray());
    }

    @Test
    void canonicalSnapshotPreservesHashedLongPrecisionEvenWhenProjectionUsesLegacyDoubleVector() {
        long hashAboveDoublePrecision = 9_007_199_254_740_993L;
        FeatureSnapshot snapshot = new FeatureSnapshot(2.0, 0.1, 0.7, 3.0, 4, 5L,
            hashAboveDoublePrecision, 7);

        assertThat(snapshot.headerFingerprintHash()).isEqualTo(hashAboveDoublePrecision);
        assertThat(snapshot.project(FeatureProjectionId.EXPORT)[5]).isEqualTo((double) hashAboveDoublePrecision);
    }

    @Test
    void missingProjectionFeatureNameIsRejected() {
        FeatureSnapshot snapshot = new FeatureSnapshot(1.0, 0.2, 0.3, -1.0, 4, 5L, 6L, 7);
        assertThatThrownBy(() -> snapshot.numericValue("doesNotExist"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown feature name");
    }

    @Test
    void nonFiniteCanonicalFeatureValuesAreRejected() {
        assertThatThrownBy(() -> new FeatureSnapshot(Double.NaN, 0.1, 0.2, 1.0, 1, 1L, 1L, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("requestsPerWindow");
        assertThatThrownBy(() -> new FeatureSnapshot(1.0, Double.POSITIVE_INFINITY, 0.2, 1.0, 1, 1L, 1L, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("endpointEntropy");
        assertThatThrownBy(() -> new FeatureSnapshot(1.0, 0.1, 0.2, Double.NEGATIVE_INFINITY, 1, 1L, 1L, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tokenAgeSeconds");
        assertThatThrownBy(() -> new FeatureSnapshot(1.0, 0.1, 0.2, 1.0, -1, 1L, 1L, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parameterCount");
        assertThatThrownBy(() -> new FeatureSnapshot(1.0, 0.1, 0.2, 1.0, 1, -1L, 1L, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("payloadSizeBytes");
    }
}
