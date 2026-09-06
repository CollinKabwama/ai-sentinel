package dev.aisentinel.core.model;

import dev.aisentinel.core.scoring.StatisticalFeatureNames;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertThat(FeatureSchema.VERSION_ID).isEqualTo(Integer.toString(FeatureSchema.VERSION));
        assertThat(FeatureSchema.supportsVersion("1")).isTrue();
        assertThat(FeatureSchema.STATISTICAL_FEATURE_NAMES).hasSize(FeatureSchema.STATISTICAL_DIMENSION);
        assertThat(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES).hasSize(FeatureSchema.ISOLATION_FOREST_DIMENSION);
        assertThat(FeatureSchema.EXPORT_FEATURE_NAMES).hasSize(FeatureSchema.EXPORT_DIMENSION);
    }

    @Test
    void canonicalDefinitionsAndProjectionsDescribeCurrentBehavior() {
        assertThat(FeatureSchema.CANONICAL_FEATURES)
            .extracting(FeatureDefinition::name)
            .containsExactly(
                "requestsPerWindow",
                "endpointEntropy",
                "endpointConcentration",
                "tokenAgeSeconds",
                "parameterCount",
                "payloadSizeBytes",
                "headerFingerprintHash",
                "ipBucket"
            );
        assertThat(FeatureSchema.CANONICAL_FEATURES)
            .extracting(FeatureDefinition::valueType)
            .containsExactly(
                FeatureValueType.DECIMAL,
                FeatureValueType.DECIMAL,
                FeatureValueType.DECIMAL,
                FeatureValueType.DECIMAL,
                FeatureValueType.INTEGER,
                FeatureValueType.LONG,
                FeatureValueType.HASHED_LONG,
                FeatureValueType.BUCKETED_INTEGER
            );
        assertThat(FeatureSchema.projection(FeatureProjectionId.STATISTICAL).orderedFeatureNames())
            .containsExactlyElementsOf(FeatureSchema.STATISTICAL_FEATURE_NAMES);
        assertThat(FeatureSchema.projection(FeatureProjectionId.ISOLATION_FOREST).orderedFeatureNames())
            .containsExactlyElementsOf(FeatureSchema.ISOLATION_FOREST_FEATURE_NAMES);
        assertThat(FeatureSchema.projection(FeatureProjectionId.EXPORT).orderedFeatureNames())
            .containsExactlyElementsOf(FeatureSchema.EXPORT_FEATURE_NAMES);
    }

    @Test
    void featureDefinitionsRejectBlankContractNames() {
        assertThatThrownBy(() -> new FeatureDefinition("", FeatureValueType.DECIMAL, "", "description"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("name");
        assertThatThrownBy(() -> new FeatureDefinition("feature", FeatureValueType.DECIMAL, "", " "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("description");
    }

    @Test
    void projectionsRejectBlankOrDuplicateFeatureNames() {
        assertThatThrownBy(() -> new FeatureProjection(FeatureProjectionId.EXPORT, List.of("requestsPerWindow", "")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
        assertThatThrownBy(() -> new FeatureProjection(FeatureProjectionId.EXPORT,
            List.of("requestsPerWindow", "requestsPerWindow")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicates");
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
        assertThatThrownBy(() -> FeatureSchema.CANONICAL_FEATURES.add(
            new FeatureDefinition("x", FeatureValueType.DECIMAL, "", "desc")))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unknownFeatureSchemaVersionIsRejected() {
        assertThatThrownBy(() -> FeatureSchema.requireSupportedVersion("2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported feature schema version");
    }
}
