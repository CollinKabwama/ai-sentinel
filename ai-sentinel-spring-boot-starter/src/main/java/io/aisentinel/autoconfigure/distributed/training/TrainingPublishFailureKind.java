package io.aisentinel.autoconfigure.distributed.training;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.concurrent.TimeoutException;

/**
 * Classifies transport failures for metrics and actuator summaries (best-effort; avoids a new framework).
 */
enum TrainingPublishFailureKind {
    GENERIC,
    KAFKA_TIMEOUT,
    SERIALIZATION;

    static TrainingPublishFailureKind classify(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof TimeoutException) {
                return KAFKA_TIMEOUT;
            }
            if (c instanceof JsonProcessingException) {
                return SERIALIZATION;
            }
        }
        return GENERIC;
    }
}
