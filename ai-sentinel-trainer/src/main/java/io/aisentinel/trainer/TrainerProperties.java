package io.aisentinel.trainer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds {@code aisentinel.trainer.*} (tenant, buffer, train schedule, IF hyperparameters, admission, dedup, Kafka).
 */
@Component
@Data
@ConfigurationProperties(prefix = "aisentinel.trainer")
public class TrainerProperties {

    private String tenantId = "default";
    private Registry registry = new Registry();
    private Buffer buffer = new Buffer();
    private Train train = new Train();
    private IfModel ifModel = new IfModel();
    private Admission admission = new Admission();
    private Dedup dedup = new Dedup();
    private Kafka kafka = new Kafka();

    @Data
    public static class Registry {
        /** Same root layout as {@code FilesystemModelRegistry} on serving nodes. */
        private String filesystemRoot = "./var/aisentinel-model-registry";
    }

    @Data
    public static class Buffer {
        private int maxSamples = 50_000;
    }

    @Data
    public static class Train {
        /** Minimum delay between train cycles (ms). */
        private long intervalMillis = 300_000L;
        private int minSamples = 100;
    }

    @Data
    public static class IfModel {
        private int numTrees = 100;
        private int maxDepth = 10;
        private long randomSeed = 42L;
    }

    @Data
    public static class Admission {
        /** Minimum composite score to admit (inclusive). */
        private double minCompositeScore = 0.0;
        /**
         * When IF score is present and above this, skip sample (anti-poisoning). NaN threshold disables.
         */
        private double maxIsolationForestScore = 0.7;
    }

    @Data
    public static class Dedup {
        /**
         * Max distinct {@code eventId} values remembered for duplicate suppression; 0 disables.
         * Bounded; evicts oldest when full.
         */
        private int maxRecentEventIds = 50_000;
    }

    @Data
    public static class Kafka {
        private boolean enabled = false;
        private String topic = "aisentinel.training.candidates";
        private String groupId = "aisentinel-trainer";
    }
}
