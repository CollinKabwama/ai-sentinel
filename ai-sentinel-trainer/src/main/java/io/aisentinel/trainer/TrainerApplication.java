package io.aisentinel.trainer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the optional {@code ai-sentinel-trainer} Spring Boot app: consumes training candidates (Kafka when
 * enabled), trains IF models, publishes to a filesystem registry—separate from serving nodes.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class TrainerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrainerApplication.class, args);
    }
}
