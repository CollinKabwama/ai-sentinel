package io.aisentinel.autoconfigure.distributed.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.distributed.training.TrainingCandidateRecord;
import io.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrainingCandidateJsonTest {

    @Test
    void jsonContainsEventIdAndHashesNotRawEndpoint() throws Exception {
        String rawEndpoint = "/accounts/secret-id/reset?token=abc";
        String epHash = TrainingFingerprintHashes.sha256HexUtf8(rawEndpoint);
        String ekHash = TrainingFingerprintHashes.sha256HexUtf8("ek");
        var record = new TrainingCandidateRecord(
            2,
            "550e8400-e29b-41d4-a716-446655440000",
            "tenant",
            "node",
            "idh",
            epHash,
            ekHash,
            99L,
            new double[] {1, 2, 3, 4, 5},
            new double[] {1, 2, 3, 4, 5, 6, 7},
            0.1,
            0.2,
            0.55,
            "MONITOR",
            "ENFORCE",
            true,
            false
        );
        Map<String, Object> m = TrainingCandidateJson.toMap(record);
        ObjectMapper om = new ObjectMapper();
        String json = om.writeValueAsString(m);
        assertThat(json).contains("eventId");
        assertThat(json).contains("550e8400-e29b-41d4-a716-446655440000");
        assertThat(json).contains("endpointSha256Hex");
        assertThat(json).contains(epHash);
        assertThat(json).doesNotContain(rawEndpoint);
        assertThat(json).doesNotContain("\"endpoint\":");
        assertThat(json).doesNotContain("\"enforcementKey\":");
        assertThat(m).doesNotContainKey("endpoint");
        assertThat(m).doesNotContainKey("enforcementKey");
    }
}
