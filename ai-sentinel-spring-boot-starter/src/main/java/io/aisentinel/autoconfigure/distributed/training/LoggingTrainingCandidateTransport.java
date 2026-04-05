package io.aisentinel.autoconfigure.distributed.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.distributed.training.TrainingCandidateRecord;
import lombok.extern.slf4j.Slf4j;

/**
 * Development-oriented transport: one JSON line per candidate at INFO (bounded payload).
 */
@Slf4j
public final class LoggingTrainingCandidateTransport implements TrainingCandidateTransport {

    private final ObjectMapper objectMapper;

    public LoggingTrainingCandidateTransport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public void send(TrainingCandidateRecord record) throws Exception {
        log.info("aisentinel.training.candidate {}", objectMapper.writeValueAsString(TrainingCandidateJson.toMap(record)));
    }
}
