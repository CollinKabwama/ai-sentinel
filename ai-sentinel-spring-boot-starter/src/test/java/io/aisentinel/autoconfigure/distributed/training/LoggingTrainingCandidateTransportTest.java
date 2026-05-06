package io.aisentinel.autoconfigure.distributed.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.distributed.training.TrainingCandidateRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingTrainingCandidateTransportTest {

    @Test
    void sendSerializesWithoutThrowing() {
        String z = "0".repeat(64);
        TrainingCandidateRecord record = new TrainingCandidateRecord(
            TrainingCandidateRecord.CURRENT_SCHEMA_VERSION,
            "evt-1",
            "tenant",
            "node",
            "identityhash12",
            z,
            z,
            1L,
            new double[5],
            new double[7],
            0.1,
            null,
            0.2,
            null,
            null,
            "MONITOR",
            "ENFORCE",
            true,
            false);
        LoggingTrainingCandidateTransport transport = new LoggingTrainingCandidateTransport(new ObjectMapper());

        assertThatCode(() -> transport.send(record)).doesNotThrowAnyException();
    }
}
