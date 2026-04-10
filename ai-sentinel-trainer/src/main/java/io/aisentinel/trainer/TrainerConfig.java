package io.aisentinel.trainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Minimal trainer wiring (e.g. shared {@link com.fasterxml.jackson.databind.ObjectMapper} bean). */
@Configuration
public class TrainerConfig {

    @Bean
    public ObjectMapper trainerObjectMapper() {
        return new ObjectMapper();
    }
}
