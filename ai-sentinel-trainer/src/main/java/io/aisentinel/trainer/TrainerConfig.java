package io.aisentinel.trainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TrainerConfig {

    @Bean
    public ObjectMapper trainerObjectMapper() {
        return new ObjectMapper();
    }
}
