package dev.aisentinel.core.scenario;

import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.store.BaselineStore;
import dev.aisentinel.core.telemetry.TelemetryEmitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Framework-independent runner: real {@link DefaultFeatureExtractor} → {@link AnomalyScorer}
 * → {@link SentinelDecisionEngine} → {@link PolicyEngine}. Test-only; no Spring.
 */
final class DetectionScenarioRunner {

    private final FeatureExtractor extractor;
    private final AnomalyScorer scorer;
    private final SentinelDecisionEngine engine;

    DetectionScenarioRunner(FeatureExtractor extractor, AnomalyScorer scorer, SentinelDecisionEngine engine) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.scorer = Objects.requireNonNull(scorer, "scorer");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Statistical-only stack with default thresholds, no trust/fusion/grace/quarantine.
     */
    static DetectionScenarioRunner statisticalOnly() {
        BaselineStore requestCounts = new BaselineStore(Duration.ofMinutes(5), 100_000);
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(requestCounts, 100_000, 300_000L);
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQuarantinedHandler.INSTANCE,
            NoopTelemetry.INSTANCE,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
        return new DetectionScenarioRunner(extractor, scorer, engine);
    }

    List<ScenarioObservation> run(DetectionScenario scenario) {
        List<ScenarioObservation> out = new ArrayList<>(scenario.steps().size());
        int step = 0;
        for (DetectionScenarioStep s : scenario.steps()) {
            step++;
            RequestContext ctx = new RequestContext();
            RequestFeatures features = extractor.extract(s.request(), s.identityHash(), ctx);
            RiskDecision decision = engine.evaluate(s.request(), s.identityHash(), features, ctx);
            if (decision == null) {
                throw new IllegalStateException("SentinelDecisionEngine returned null (scoring fail-open) at step " + step);
            }
            out.add(new ScenarioObservation(
                step,
                features,
                decision.anomalyScore(),
                decision.policyScore(),
                decision.action(),
                decision.startupGraceActive()
            ));
        }
        return out;
    }

    AnomalyScorer scorer() {
        return scorer;
    }

    record DetectionScenarioStep(String identityHash, HttpRequestView request) {}

    record DetectionScenario(String id, List<DetectionScenarioStep> steps) {}

    record ScenarioObservation(
        int stepNumber,
        RequestFeatures features,
        double anomalyScore,
        double policyScore,
        EnforcementAction action,
        boolean startupGraceActive
    ) {}

    private enum NoopTelemetry implements TelemetryEmitter {
        INSTANCE;

        @Override
        public void emit(dev.aisentinel.core.telemetry.TelemetryEvent event) {
            // scenario harness: telemetry not under test
        }
    }

    private enum NeverQuarantinedHandler implements EnforcementHandler {
        INSTANCE;

        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            throw new AssertionError("scenario harness must not apply enforcement via the decision engine");
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }
}
