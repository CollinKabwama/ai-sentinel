package io.aisentinel.trainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.distributed.training.TrainingCandidateRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrainingCandidateMessageParserTest {

    @Test
    void parsesValidPayload() throws Exception {
        String json = """
            {"schemaVersion":2,"eventId":"e1","tenantId":"default","nodeId":"n","identityHash":"h",\
            "endpointSha256Hex":"%s","enforcementKeySha256Hex":"%s","observedAtEpochMillis":1,\
            "isolationForestFeatures":[1,2,3,4,5],"statisticalFeatures":[1,2,3,4,5,6,7],\
            "compositeScore":0.8,"policyAction":"MONITOR","sentinelMode":"ENFORCE","requestProceeded":true,\
            "startupGraceActive":false}\
            """.formatted(
            "a".repeat(64),
            "b".repeat(64)
        );
        TrainingCandidateMessageParser p = new TrainingCandidateMessageParser(new ObjectMapper());
        TrainingCandidateRecord r = p.parse(json);
        assertThat(r.tenantId()).isEqualTo("default");
        assertThat(r.compositeScore()).isEqualTo(0.8);
        assertThat(r.trustScore()).isNull();
        assertThat(r.fusedPolicyScore()).isNull();
        assertThat(r.isolationForestFeatures()).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void parsesSchema3WithTrustAndFused() throws Exception {
        String json = """
            {"schemaVersion":3,"eventId":"e1","tenantId":"default","nodeId":"n","identityHash":"h",\
            "endpointSha256Hex":"%s","enforcementKeySha256Hex":"%s","observedAtEpochMillis":1,\
            "isolationForestFeatures":[1,2,3,4,5],"statisticalFeatures":[1,2,3,4,5,6,7],\
            "compositeScore":0.35,"trustScore":0.1,"fusedPolicyScore":0.72,"policyAction":"THROTTLE",\
            "sentinelMode":"ENFORCE","requestProceeded":true,"startupGraceActive":false}\
            """.formatted(
            "a".repeat(64),
            "b".repeat(64)
        );
        TrainingCandidateMessageParser p = new TrainingCandidateMessageParser(new ObjectMapper());
        TrainingCandidateRecord r = p.parse(json);
        assertThat(r.schemaVersion()).isEqualTo(3);
        assertThat(r.compositeScore()).isEqualTo(0.35);
        assertThat(r.trustScore()).isEqualTo(0.1);
        assertThat(r.fusedPolicyScore()).isEqualTo(0.72);
    }

    @Test
    void rejectsWrongSchema() {
        String json = "{\"schemaVersion\":99,\"compositeScore\":0.1}";
        TrainingCandidateMessageParser p = new TrainingCandidateMessageParser(new ObjectMapper());
        assertThatThrownBy(() -> p.parse(json)).isInstanceOf(Exception.class);
    }
}
