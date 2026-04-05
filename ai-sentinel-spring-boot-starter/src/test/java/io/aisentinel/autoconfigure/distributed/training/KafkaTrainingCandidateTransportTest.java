package io.aisentinel.autoconfigure.distributed.training;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTrainingCandidateTransportTest {

    @Test
    void clampSendTimeoutMillis() {
        assertThat(KafkaTrainingCandidateTransport.clampSendTimeoutMillis(0)).isEqualTo(1);
        assertThat(KafkaTrainingCandidateTransport.clampSendTimeoutMillis(50)).isEqualTo(50);
        assertThat(KafkaTrainingCandidateTransport.clampSendTimeoutMillis(999_999))
            .isEqualTo(KafkaTrainingCandidateTransport.MAX_SEND_TIMEOUT_MILLIS);
    }
}
