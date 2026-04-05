package io.aisentinel.autoconfigure.distributed.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.distributed.training.TrainingCandidateRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Publishes JSON candidates to Kafka. Intended for optional {@code spring-kafka} on the classpath.
 * Send wait is bounded and clamped so slow brokers cannot pin workers for unbounded time.
 */
public final class KafkaTrainingCandidateTransport implements TrainingCandidateTransport {

    /** Hard upper bound for worker blocking on producer send completion (telemetry path). */
    public static final long MAX_SEND_TIMEOUT_MILLIS = 10_000L;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topic;
    private final ObjectMapper objectMapper;
    private final long sendTimeoutMillis;

    public KafkaTrainingCandidateTransport(KafkaTemplate<String, String> kafkaTemplate,
                                           String topic,
                                           ObjectMapper objectMapper,
                                           long sendTimeoutMillis) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic != null && !topic.isBlank() ? topic : "aisentinel.training.candidates";
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.sendTimeoutMillis = clampSendTimeoutMillis(sendTimeoutMillis);
    }

    /**
     * Clamps configured timeout to {@code [1, MAX_SEND_TIMEOUT_MILLIS]}.
     */
    public static long clampSendTimeoutMillis(long configuredMillis) {
        long c = Math.max(1L, configuredMillis);
        return Math.min(c, MAX_SEND_TIMEOUT_MILLIS);
    }

    @Override
    public void send(TrainingCandidateRecord record) throws Exception {
        String json = objectMapper.writeValueAsString(TrainingCandidateJson.toMap(record));
        String key = record.identityHash() != null && !record.identityHash().isBlank()
            ? record.identityHash()
            : record.tenantId();
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, json);
        future.get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
    }
}
