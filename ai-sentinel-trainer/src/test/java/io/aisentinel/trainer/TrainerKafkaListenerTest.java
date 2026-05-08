package io.aisentinel.trainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrainerKafkaListenerTest {

    private static final class CapturingOrchestrator extends TrainerOrchestrator {
        String lastMessage;

        CapturingOrchestrator() {
            super(new TrainerProperties(), new ObjectMapper(), new TrainerMetrics(new SimpleMeterRegistry()));
        }

        @Override
        public void handleKafkaMessage(String json) {
            this.lastMessage = json;
        }
    }

    @Test
    void consumeDelegatesToOrchestrator() {
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        TrainerKafkaListener listener = new TrainerKafkaListener(orchestrator);
        listener.consume("{\"schemaVersion\":1}");
        assertThat(orchestrator.lastMessage).isEqualTo("{\"schemaVersion\":1}");
    }
}
