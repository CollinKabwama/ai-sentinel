package io.aisentinel.autoconfigure.config;

import io.aisentinel.core.enforcement.EnforcementScope;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "ai.sentinel")
public class SentinelProperties {

    public enum Mode { OFF, MONITOR, ENFORCE }

    private boolean enabled = true;
    private Mode mode = Mode.ENFORCE;
    private List<String> excludePaths = List.of("/actuator/**", "/health", "/health/**", "/static/**", "/favicon.ico");
    private int blockStatusCode = 429;
    private long quarantineDurationMs = 300_000;
    private double throttleRequestsPerSecond = 5.0;
    private Duration baselineTtl = Duration.ofMinutes(5);
    private int baselineMaxKeys = 100_000;
    /** Max keys for internal maps (stateByKey, endpointHistory, throttle, quarantine). Default 100_000. */
    private int internalMapMaxKeys = 100_000;
    /** TTL for internal map entries (evict after this period of no access). Default 5 minutes. */
    private Duration internalMapTtl = Duration.ofMinutes(5);
    /** Trusted proxy IPs/CIDRs; if empty, forwarded headers are not used. When remote is trusted, client IP is taken from X-Forwarded-For (rightmost-untrusted), Forwarded, or X-Real-IP. */
    private List<String> trustedProxies = List.of();
    /** After startup, enforcement is limited to MONITOR for this duration (0 disables). */
    private Duration startupGracePeriod = Duration.ZERO;
    /** Whether throttle/quarantine keys are per identity only or identity+endpoint. */
    private EnforcementScope enforcementScope = EnforcementScope.IDENTITY_ENDPOINT;
    /** Min samples per key before using real score (cold-start); below this return warmup-score. Default 2. */
    private int warmupMinSamples = 2;
    /** Score returned during warmup (cold-start) to avoid bypass. Default 0.4 (MONITOR). */
    private double warmupScore = 0.4;

    /** Policy: scores at or above this map to MONITOR (below elevated). Default 0.2. */
    private double thresholdModerate = 0.2;
    /** Policy: scores at or above this map to THROTTLE. Default 0.4. */
    private double thresholdElevated = 0.4;
    /** Policy: scores at or above this map to BLOCK. Default 0.6. */
    private double thresholdHigh = 0.6;
    /** Policy: scores at or above this map to QUARANTINE. Default 0.8. */
    private double thresholdCritical = 0.8;

    private IsolationForest isolationForest = new IsolationForest();
    private Telemetry telemetry = new Telemetry();
    /** Phase 5 — distributed coordination (disabled by default; see README). */
    private Distributed distributed = new Distributed();

    @Data
    public static class Telemetry {
        private String logVerbosity = "ANOMALY_ONLY";
        private double logScoreThreshold = 0.4;
        private int logSampleRate = 100;

        public void setLogSampleRate(int v) {
            this.logSampleRate = Math.max(1, v);
        }
    }

    @Data
    public static class IsolationForest {
        private boolean enabled = false;
        private int trainingBufferSize = 10_000;
        private int minTrainingSamples = 100;
        private Duration retrainInterval = Duration.ofMinutes(5);
        private long randomSeed = 42L;
        private double scoreWeight = 0.5;
        /** Fraction of requests to add to training buffer (0.0–1.0). Default 0.1 */
        private double sampleRate = 0.1;
        /** Score returned when no model is loaded (fallback). Default 0.5 */
        private double fallbackScore = 0.5;
        /** Number of trees in the Isolation Forest. Default 100 */
        private int numTrees = 100;
        /** Maximum tree depth. Default 10 */
        private int maxDepth = 10;
        /** When a model is loaded, training buffer rejects samples with IF score above this (anti-poisoning). Default 0.7 */
        private double trainingRejectionScoreThreshold = 0.7;
    }

    @Data
    public static class Distributed {
        /** Master switch for Phase 5 integration beans (Redis/Kafka wiring comes in later steps). */
        private boolean enabled = false;
        /** Logical tenant segment in shared Redis keys (see README distributed properties). */
        private String tenantId = "default";
        /** Kafka topic for {@link io.aisentinel.distributed.training.TrainingCandidateRecord} export. */
        private String trainingCandidatesTopic = "aisentinel.training.candidates";
        /**
         * When true, {@link io.aisentinel.distributed.enforcement.ClusterAwareEnforcementHandler} merges
         * local quarantine with {@link io.aisentinel.distributed.quarantine.ClusterQuarantineReader} (Redis or noop).
         */
        private boolean clusterQuarantineReadEnabled = false;
        /**
         * When true, local {@link io.aisentinel.core.enforcement.CompositeEnforcementHandler} QUARANTINE actions
         * also publish to Redis (requires {@link #enabled}, {@link Redis#enabled}, {@code StringRedisTemplate}).
         */
        private boolean clusterQuarantineWriteEnabled = false;
        /** Redis cluster-quarantine client settings (no effect unless {@link #isEnabled()} and redis.enabled). */
        private Redis redis = new Redis();
        /** Bounded local cache for Redis quarantine GETs (request-path protection). */
        private Cache cache = new Cache();

        @Data
        public static class Redis {
            /**
             * When true and {@link Distributed#enabled} and {@link Distributed#clusterQuarantineReadEnabled}
             * and a {@link org.springframework.data.redis.core.StringRedisTemplate} bean exists,
             * {@link io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader} is registered.
             */
            private boolean enabled = false;
            /** First segment of Redis keys; full key: {@code {keyPrefix}:{tenant}:q:{enforcementKey}}. */
            private String keyPrefix = "aisentinel";
            /**
             * Upper bound for how long the caller waits on a cluster quarantine Redis GET (async future).
             * Configure {@code spring.data.redis.timeout} (Lettuce command timeout) to a similar or lower value so
             * the client does not hold work longer than this; see README (distributed Redis / timeouts).
             */
            private Duration lookupTimeout = Duration.ofMillis(50);
            /**
             * Max concurrent async cluster quarantine Redis SETs per JVM; additional {@code publishQuarantine} calls
             * are dropped (metric) without blocking the caller. Bounded to a sane range when bound from config.
             */
            private int maxInFlightQuarantineWrites = 256;
        }

        @Data
        public static class Cache {
            /**
             * When false, {@link io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader}
             * skips the local cache (more Redis round-trips within the lookup timeout).
             */
            private boolean enabled = true;
            /** Wall-clock TTL for positive cache entries (parsed until-millis from Redis). */
            private Duration ttl = Duration.ofSeconds(2);
            /**
             * TTL for negative (not quarantined / miss) cache lines. If unset, derived as
             * {@code max(100ms, min(positiveTtl/2, 2s))}.
             */
            private Duration negativeTtl;
            /** Max cached enforcement keys; evicts when exceeded. */
            private int maxEntries = 10_000;
        }
    }
}
