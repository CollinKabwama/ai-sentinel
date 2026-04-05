package io.aisentinel.trainer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "aisentinel.trainer.kafka.enabled", havingValue = "true")
public class TrainerKafkaListener {

    private final TrainerOrchestrator orchestrator;

    public TrainerKafkaListener(TrainerOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @KafkaListener(
        topics = "${aisentinel.trainer.kafka.topic:aisentinel.training.candidates}",
        groupId = "${aisentinel.trainer.kafka.group-id:aisentinel-trainer}"
    )
    public void consume(String message) {
        orchestrator.handleKafkaMessage(message);
    }
}
