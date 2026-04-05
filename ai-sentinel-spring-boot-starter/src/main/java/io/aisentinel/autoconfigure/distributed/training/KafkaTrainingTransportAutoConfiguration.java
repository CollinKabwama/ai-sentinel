package io.aisentinel.autoconfigure.distributed.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aisentinel.autoconfigure.config.SentinelAutoConfiguration;
import io.aisentinel.autoconfigure.config.SentinelProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Optional Kafka transport when {@code spring-kafka} is on the classpath. Skipped entirely otherwise.
 */
@AutoConfiguration(before = SentinelAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
public class KafkaTrainingTransportAutoConfiguration {

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "ai.sentinel.distributed", name = "training-kafka-enabled", havingValue = "true")
    @ConditionalOnBean(KafkaTemplate.class)
    public TrainingCandidateTransport kafkaTrainingCandidateTransport(
        KafkaTemplate<String, String> kafkaTemplate,
        SentinelProperties props,
        ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper om = objectMapperProvider.getIfAvailable();
        if (om == null) {
            om = new ObjectMapper();
        }
        var d = props.getDistributed();
        long ms = d.getTrainingPublishTimeout() != null
            ? Math.max(1L, d.getTrainingPublishTimeout().toMillis())
            : 2000L;
        return new KafkaTrainingCandidateTransport(
            kafkaTemplate,
            d.getTrainingCandidatesTopic(),
            om,
            KafkaTrainingCandidateTransport.clampSendTimeoutMillis(ms));
    }
}
