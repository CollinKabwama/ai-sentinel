package io.aisentinel.distributed.training;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingCandidateRecordTest {

    @Test
    void normalizesHashesAndCopiesArrays() {
        String longTenant = "t".repeat(200);
        double[] ifVec = {1, 2, 3};
        double[] stVec = {9, 8, 7, 6, 5, 4, 3};
        String epHash = TrainingFingerprintHashes.sha256HexUtf8("/api/x");
        String ekHash = TrainingFingerprintHashes.sha256HexUtf8("k");
        var r = new TrainingCandidateRecord(
            TrainingCandidateRecord.CURRENT_SCHEMA_VERSION,
            "evt-uuid-here",
            longTenant,
            "n",
            "id",
            epHash,
            ekHash,
            1L,
            ifVec,
            stVec,
            0.1,
            0.2,
            0.3,
            "ALLOW",
            "ENFORCE",
            true,
            false
        );
        assertThat(r.tenantId()).hasSize(128);
        assertThat(r.eventId()).isEqualTo("evt-uuid-here");
        assertThat(r.endpointSha256Hex()).isEqualTo(epHash);
        assertThat(r.enforcementKeySha256Hex()).isEqualTo(ekHash);
        assertThat(r.isolationForestFeatures()).containsExactly(1, 2, 3, 0, 0);
        assertThat(r.statisticalFeatures()).containsExactly(9, 8, 7, 6, 5, 4, 3);
        ifVec[0] = 99;
        assertThat(r.isolationForestFeatures()[0]).isEqualTo(1.0);
    }

    @Test
    void invalidHashBecomesZeroHex() {
        var r = new TrainingCandidateRecord(
            2,
            "e",
            "t",
            "n",
            "id",
            "short",
            null,
            1L,
            new double[5],
            new double[7],
            null,
            null,
            0.5,
            "MONITOR",
            "ENFORCE",
            true,
            false
        );
        assertThat(r.endpointSha256Hex()).isEqualTo("0".repeat(64));
        assertThat(r.enforcementKeySha256Hex()).isEqualTo("0".repeat(64));
    }
}
