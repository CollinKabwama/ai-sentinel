package dev.aisentinel.autoconfigure.distributed.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aisentinel.distributed.training.TrainingCandidateRecord;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Development-oriented transport: one JSON line per candidate at INFO (bounded payload).
 * Identity hashes are masked in the log line (same truncation style as telemetry); the underlying record is unchanged.
 */
@Slf4j
public final class LoggingTrainingCandidateTransport implements TrainingCandidateTransport {

    private final ObjectMapper objectMapper;

    public LoggingTrainingCandidateTransport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public void send(TrainingCandidateRecord record) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>(TrainingCandidateJson.toMap(record));
        Object hash = payload.get("identityHash");
        if (hash instanceof String s) {
            payload.put("identityHash", maskHash(s));
        }
        log.info("aisentinel.training.candidate {}", objectMapper.writeValueAsString(payload));
    }

    private static String maskHash(String h) {
        if (h == null || h.length() < 8) {
            return "***";
        }
        return h.substring(0, 4) + "***" + h.substring(h.length() - 4);
    }
}
