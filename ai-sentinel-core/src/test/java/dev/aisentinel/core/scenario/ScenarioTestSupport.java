package dev.aisentinel.core.scenario;

import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;

/**
 * Shared test-only fixtures for detection characterization (no production changes).
 */
final class ScenarioTestSupport {

    static final double WARMUP_SCORE = 0.4;
    static final double THRESHOLD_MODERATE = 0.2;
    static final double THRESHOLD_ELEVATED = 0.4;
    static final double THRESHOLD_HIGH = 0.6;
    static final double THRESHOLD_CRITICAL = 0.8;
    static final double MIN_STD = 1e-6;

    private ScenarioTestSupport() {
    }

    static StatisticalScorer newStatisticalScorer() {
        return new StatisticalScorer(100_000, 300_000L, 2, 0.4);
    }

    static ThresholdPolicyEngine newDefaultPolicy() {
        return new ThresholdPolicyEngine(
            THRESHOLD_MODERATE, THRESHOLD_ELEVATED, THRESHOLD_HIGH, THRESHOLD_CRITICAL);
    }

    static SentinelDecisionEngine newEngine(StatisticalScorer scorer) {
        return new SentinelDecisionEngine(
            scorer,
            newDefaultPolicy(),
            NeverQuarantined.INSTANCE,
            NoopTel.INSTANCE,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
    }

    static MapHttpRequestView shell(String endpoint) {
        return new MapHttpRequestView().requestUri(endpoint).method("GET").remoteAddr("203.0.113.50");
    }

    static RequestFeatures features(String identity, String endpoint, double rpw,
                                    double entropy, double tokenAge, int params, long payload,
                                    long headerFp, int ipBucket) {
        return RequestFeatures.builder()
            .identityHash(identity)
            .endpoint(endpoint)
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(rpw)
            .endpointEntropy(entropy)
            .tokenAgeSeconds(tokenAge)
            .parameterCount(params)
            .payloadSizeBytes(payload)
            .headerFingerprintHash(headerFp)
            .ipBucket(ipBucket)
            .build();
    }

    static RequestFeatures baseFeatures(String identity, String endpoint, double rpw) {
        return features(identity, endpoint, rpw, 0.1, -1, 0, 0L, 42L, 7);
    }

    /**
     * Test-only evaluate: real scorer + policy, with caller-controlled {@code update()}.
     * Does <strong>not</strong> use {@link SentinelDecisionEngine} (which always updates).
     */
    static GatedObservation gatedEvaluate(StatisticalScorer scorer, PolicyEngine policy,
                                          RequestFeatures features, BaselineUpdateStrategy strategy,
                                          double scoreGateThreshold) {
        double score = scorer.score(features);
        EnforcementAction action = policy.evaluate(score, features, features.endpoint());
        boolean updated = strategy.shouldUpdate(score, action, scoreGateThreshold);
        if (updated) {
            scorer.update(features);
        }
        return new GatedObservation(score, action, updated);
    }

    enum BaselineUpdateStrategy {
        ALWAYS_UPDATE {
            @Override
            boolean shouldUpdate(double score, EnforcementAction action, double scoreGate) {
                return true;
            }
        },
        UPDATE_ON_ALLOW {
            @Override
            boolean shouldUpdate(double score, EnforcementAction action, double scoreGate) {
                return action == EnforcementAction.ALLOW;
            }
        },
        UPDATE_ON_ALLOW_OR_MONITOR {
            @Override
            boolean shouldUpdate(double score, EnforcementAction action, double scoreGate) {
                return action == EnforcementAction.ALLOW || action == EnforcementAction.MONITOR;
            }
        },
        SCORE_GATE {
            @Override
            boolean shouldUpdate(double score, EnforcementAction action, double scoreGate) {
                return score < scoreGate;
            }
        },
        DELAYED_PROMOTE {
            // Implemented with external pending counter in the comparison test.
            @Override
            boolean shouldUpdate(double score, EnforcementAction action, double scoreGate) {
                return false;
            }
        };

        abstract boolean shouldUpdate(double score, EnforcementAction action, double scoreGate);
    }

    record GatedObservation(double anomalyScore, EnforcementAction action, boolean updated) {}

    private enum NoopTel implements TelemetryEmitter {
        INSTANCE;

        @Override
        public void emit(dev.aisentinel.core.telemetry.TelemetryEvent event) {
        }
    }

    private enum NeverQuarantined implements EnforcementHandler {
        INSTANCE;

        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("decision engine must not apply enforcement");
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }
}
