package dev.aisentinel.core.model;

import dev.aisentinel.core.scoring.StatisticalFeatureNames;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureSchemaContractTest {

    @Test
    void generatedVectorsMatchDeclaredDimensionsAndOrder() {
        RequestFeatures f = RequestFeatures.builder()
            .identityHash("h").endpoint("/e").timestampMillis(1L)
            .requestsPerWindow(2.0).endpointEntropy(0.1).endpointConcentration(0.5)
            .tokenAgeSeconds(3.0).parameterCount(4).payloadSizeBytes(5)
            .headerFingerprintHash(6).ipBucket(7).build();

        double[] statistical = f.toStatisticalArray();
        assertThat(statistical).hasSize(FeatureSchema.STATISTICAL_DIMENSION);
        assertThat(statistical).containsExactly(2.0, 0.1, 0.5, 3.0, 4.0, 5.0);
        assertThat(FeatureSchema.STATISTICAL_FEATURE_NAMES).containsExactly(
            "requestsPerWindow", "endpointEntropy", "endpointConcentration",
            "tokenAgeSeconds", "parameterCount", "payloadSizeBytes");
        assertThat(StatisticalFeatureNames.NAMES).containsExactly(
            "requestsPerWindow", "endpointEntropy", "endpointConcentration",
            "tokenAgeSeconds", "parameterCount", "payloadSizeBytes");

        double[] isolationForest = f.toIsolationForestArray();
        assertThat(isolationForest).hasSize(FeatureSchema.ISOLATION_FOREST_DIMENSION);
        assertThat(isolationForest).containsExactly(2.0, 0.1, 3.0, 4.0, 5.0);
        assertThat(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES).containsExactly(
            "requestsPerWindow", "endpointEntropy", "tokenAgeSeconds",
            "parameterCount", "payloadSizeBytes");

        double[] export = f.toArray();
        assertThat(export).hasSize(FeatureSchema.EXPORT_DIMENSION);
        assertThat(export).containsExactly(2.0, 0.1, 3.0, 4.0, 5.0, 6.0, 7.0);
        assertThat(FeatureSchema.EXPORT_FEATURE_NAMES).containsExactly(
            "requestsPerWindow", "endpointEntropy", "tokenAgeSeconds", "parameterCount",
            "payloadSizeBytes", "headerFingerprintHash", "ipBucket");
    }

    @Test
    void schemaVersionDocumentsCurrentLayout() {
        assertThat(FeatureSchema.VERSION).isEqualTo(1);
        assertThat(FeatureSchema.STATISTICAL_FEATURE_NAMES).hasSize(FeatureSchema.STATISTICAL_DIMENSION);
        assertThat(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES).hasSize(FeatureSchema.ISOLATION_FOREST_DIMENSION);
        assertThat(FeatureSchema.EXPORT_FEATURE_NAMES).hasSize(FeatureSchema.EXPORT_DIMENSION);
    }

    @Test
    void dimensionValidationRejectsWrongLength() {
        assertThatThrownBy(() -> FeatureSchema.requireStatisticalDimension(new double[5]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("schemaVersion=" + FeatureSchema.VERSION);
        assertThatThrownBy(() -> FeatureSchema.requireIsolationForestDimension(new double[6]))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeatureSchema.requireExportDimension(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("null");
    }

    @Test
    void featureNameListsAreImmutableContractSurfaces() {
        assertThatThrownBy(() -> FeatureSchema.STATISTICAL_FEATURE_NAMES.set(0, "changed"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES.add("changed"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> FeatureSchema.EXPORT_FEATURE_NAMES.remove(0))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
