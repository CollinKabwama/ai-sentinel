package dev.aisentinel.core.scenario;

import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.decision.RiskDecision;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gradual linear request-count ramp under production decision flow.
 * <p>
 * A unit staircase in {@code requestsPerWindow} asymptotes to MONITOR (~0.31) under continuous
 * learning (max{@code |z|} → √12/2). Default {@code ALLOW_OR_MONITOR} gating must remain at least
 * as elevated as always-update at the late probe and must <em>not</em> freeze the early staircase
 * into THROTTLE+/QUARANTINE (see benign established-baseline regressions). Abrupt volume shocks
 * that cross THROTTLE still freeze under gating ({@code SuddenStepRequestBurstScenarioTest}).
 */
class GradualEndpointRequestRampScenarioTest {

    private static final String SCENARIO_ID = "rapid-endpoint-request-burst";
    private static final String IDENTITY = "id-burst";
    private static final String ENDPOINT = "/api/checkout";
    private static final String REMOTE = "203.0.113.40";
    private static final String ACCEPT = "application/json";

    private static final int WARMUP_REQUESTS = 2;
    private static final int CALM_POST_WARMUP = 8;
    private static final int CALM_TOTAL = WARMUP_REQUESTS + CALM_POST_WARMUP;
    private static final int RAMP_EXTRA = 80;
    private static final double WARMUP_SCORE = 0.4;

    @Test
    void gradualRamp_defaultGating_reducesLateAbsorptionVersusAlwaysUpdate() {
        RampSummary gated = runRamp(ConfigurableBaselineUpdatePolicy.allowOrMonitor());
        RampSummary always = runRamp(ConfigurableBaselineUpdatePolicy.always());

        System.out.printf(Locale.ROOT,
            "%s gated: midScore=%.6f lateScore=%.6f lateAction=%s firstThrottle=%s%n",
            SCENARIO_ID, gated.midScore, gated.lateScore, gated.lateAction, gated.firstThrottleIndex);
        System.out.printf(Locale.ROOT,
            "%s always: midScore=%.6f lateScore=%.6f lateAction=%s firstThrottle=%s%n",
            SCENARIO_ID, always.midScore, always.lateScore, always.lateAction, always.firstThrottleIndex);

        assertThat(gated.warmup0).isEqualTo(WARMUP_SCORE);
        assertThat(gated.warmup1).isEqualTo(WARMUP_SCORE);
        assertThat(gated.warmupAction0).isEqualTo(EnforcementAction.MONITOR);

        // Legacy always-update still absorbs late ramp below THROTTLE.
        assertThat(always.lateScore).isLessThanOrEqualTo(always.midScore);
        assertThat(always.lateAction).isIn(EnforcementAction.ALLOW, EnforcementAction.MONITOR);

        // Default gating must not be softer than always-update at the late probe.
        assertThat(gated.lateScore)
            .as("gated late score should be at least as elevated as always-update late score")
            .isGreaterThanOrEqualTo(always.lateScore);
    }

    private static RampSummary runRamp(ConfigurableBaselineUpdatePolicy policy) {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = newEngine(scorer, policy);
        HttpRequestView request = stableRequest();

        List<RiskDecision> decisions = new ArrayList<>(CALM_TOTAL + RAMP_EXTRA);
        for (int i = 0; i < CALM_TOTAL + RAMP_EXTRA; i++) {
            // Mirror extractor-driven ramp: rpw = request index + 1 for this identity|endpoint.
            double rpw = i + 1.0;
            RiskDecision d = engine.evaluate(request, IDENTITY,
                ScenarioTestSupport.baseFeatures(IDENTITY, ENDPOINT, rpw), new RequestContext());
            assertThat(d).isNotNull();
            decisions.add(d);
        }

        RiskDecision mid = decisions.get(CALM_TOTAL - 1);
        RiskDecision late = decisions.get(decisions.size() - 1);
        Integer firstThrottle = null;
        for (int i = 0; i < decisions.size(); i++) {
            EnforcementAction a = decisions.get(i).action();
            if (a == EnforcementAction.THROTTLE || a == EnforcementAction.BLOCK || a == EnforcementAction.QUARANTINE) {
                firstThrottle = i;
                break;
            }
        }
        return new RampSummary(
            decisions.get(0).anomalyScore(),
            decisions.get(1).anomalyScore(),
            decisions.get(0).action(),
            mid.anomalyScore(),
            late.anomalyScore(),
            late.action(),
            firstThrottle
        );
    }

    private static SentinelDecisionEngine newEngine(StatisticalScorer scorer,
                                                    ConfigurableBaselineUpdatePolicy policy) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQuarantined.INSTANCE,
            NoopTel.INSTANCE,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            policy
        );
    }

    private static MapHttpRequestView stableRequest() {
        return new MapHttpRequestView()
            .requestUri(ENDPOINT)
            .method("GET")
            .remoteAddr(REMOTE)
            .header("Accept", ACCEPT);
    }

    private record RampSummary(
        double warmup0,
        double warmup1,
        EnforcementAction warmupAction0,
        double midScore,
        double lateScore,
        EnforcementAction lateAction,
        Integer firstThrottleIndex
    ) {
    }

    private enum NeverQuarantined implements EnforcementHandler {
        INSTANCE;

        @Override
        public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                             String identityHash, String endpoint) {
            return true;
        }

        @Override
        public boolean isQuarantined(String identityHash, String endpoint) {
            return false;
        }
    }

    private enum NoopTel implements TelemetryEmitter {
        INSTANCE;

        @Override
        public void emit(dev.aisentinel.core.telemetry.TelemetryEvent event) {
        }
    }
}
