package io.aisentinel.autoconfigure.config;

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
    /** Trusted proxy IPs/CIDRs; if empty, forwarded headers are not used. When remote is trusted, client IP is taken from X-Forwarded-For, Forwarded, or X-Real-IP. */
    private List<String> trustedProxies = List.of();
    /** Min samples per key before using real score (cold-start); below this return warmup-score. Default 2. */
    private int warmupMinSamples = 2;
    /** Score returned during warmup (cold-start) to avoid bypass. Default 0.4 (MONITOR). */
    private double warmupScore = 0.4;

    private IsolationForest isolationForest = new IsolationForest();
    private Telemetry telemetry = new Telemetry();

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
    }
}
