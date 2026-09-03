package dev.aisentinel.distributed.training;

import dev.aisentinel.core.model.FeatureSchema;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingFeatureSchemaAlignmentTest {

    @Test
    void trainingCandidateRecordPadsToFeatureSchemaDimensions() {
        TrainingCandidateRecord record = new TrainingCandidateRecord(
            TrainingCandidateRecord.CURRENT_SCHEMA_VERSION,
            "evt",
            "tenant",
            "node",
            "id",
            "a".repeat(64),
            "b".repeat(64),
            1L,
            new double[] {1, 2, 3},
            new double[] {1, 2, 3, 4},
            0.1,
            0.2,
            0.3,
            null,
            null,
            "ALLOW",
            "ENFORCE",
            true,
            false
        );
        assertThat(record.isolationForestFeatures()).hasSize(FeatureSchema.ISOLATION_FOREST_DIMENSION);
        assertThat(record.statisticalFeatures()).hasSize(FeatureSchema.EXPORT_DIMENSION);
    }
}
