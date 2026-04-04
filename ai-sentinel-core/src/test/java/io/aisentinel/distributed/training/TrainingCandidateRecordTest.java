package io.aisentinel.distributed.training;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrainingCandidateRecordTest {

    @Test
    void requiresFiveDimensionalFeatureVectorWhenPresent() {
        assertThatThrownBy(() -> new TrainingCandidateRecord(1, "t", "n", "id", "/a", new double[3], 0.5, 0L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsFiveFeatures() {
        var r = new TrainingCandidateRecord(1, "t", "n", "id", "/a", new double[]{1, 2, 3, 4, 5}, 0.5, 100L);
        assertThat(r.isolationForestFeaturesCopy()).containsExactly(1, 2, 3, 4, 5);
    }
}
