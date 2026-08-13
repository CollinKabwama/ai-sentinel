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
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scenario {@code sudden-step-request-burst}: stable constant-{@code requestsPerWindow} Welford baseline,
 * then an abrupt elevated level.
 * <p>
 * <strong>Test fidelity:</strong> {@code controlled RequestFeatures → scorer/policy}
 * (not full extractor E2E). {@code requestsPerWindow} is a rolling count across BaselineStore
 * buckets, not a per-second rate; the production extractor increments approximately
 * {@code 1, 2, 3, ...} per request, so a synthetic {@code 10 → 100} step cannot be produced
 * through the full extractor path without intermediate counts.
 * <p>
 * <strong>Honest scope:</strong> does <em>not</em> claim full extractor→policy E2E for the step.
 * {@link DefaultFeatureExtractor} increments {@code requestsPerWindow} by +1 per call, so a true step
 * cannot be produced through the extractor alone. This test uses:
 * <ul>
 *   <li><strong>Real</strong> {@link StatisticalScorer}, {@link SentinelDecisionEngine},
 *       {@link ThresholdPolicyEngine}</li>
 *   <li><strong>Controlled</strong> {@link RequestFeatures} fixtures (constant calm rpw, then step rpw)</li>
 *   <li>{@link MapHttpRequestView} only as a neutral request shell for {@code evaluate}</li>
 * </ul>
 * Under the default {@code ALLOW_OR_MONITOR} baseline-update policy, elevated THROTTLE+ observations do not
 * train the baseline, so sudden-step scores no longer decay from online updates. Unconditional {@code ALWAYS}
 * still decays (legacy behavior).
 */
class SuddenStepRequestBurstScenarioTest {

    private static final String SCENARIO_ID = "sudden-step-request-burst";
    private static final String IDENTITY = "id-step";
    private static final String ENDPOINT = "/api/checkout";
    private static final double CALM_RPW = 10.0;
    private static final double STEP_RPW = 100.0;
    private static final int BASELINE_UPDATES = 40;
    private static final int ELEVATED_SAMPLES = 25;
    private static final double MIN_STD = 1e-6;

    private static final Set<EnforcementAction> THROTTLE_OR_STRICTER = EnumSet.of(
        EnforcementAction.THROTTLE, EnforcementAction.BLOCK, EnforcementAction.QUARANTINE);

    @Test
    void suddenStep_defaultGating_holdsScore_alwaysUpdateStillDecays() {
        StepRun gated = runStep(ConfigurableBaselineUpdatePolicy.allowOrMonitor());
        StepRun always = runStep(ConfigurableBaselineUpdatePolicy.always());

        System.out.println(SCENARIO_ID + " gated decay table:");
        printTable(gated);
        System.out.println(SCENARIO_ID + " always decay table:");
        printTable(always);

        assertThat(THROTTLE_OR_STRICTER.contains(gated.first.action()))
            .as("first sudden-step action should reach THROTTLE+. %s", gated.evidence)
            .isTrue();
        assertThat(gated.last.anomalyScore())
            .as("gated elevated samples must not adapt downward. %s", gated.evidence)
            .isEqualTo(gated.first.anomalyScore());
        assertThat(always.last.anomalyScore())
            .as("ALWAYS mode still adapts downward. %s", always.evidence)
            .isLessThan(always.first.anomalyScore());
    }

    private static StepRun runStep(ConfigurableBaselineUpdatePolicy policy) {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = newEngine(scorer, policy);
        HttpRequestView request = new MapHttpRequestView().requestUri(ENDPOINT).method("GET");

        RequestFeatures calmFeatures = features(CALM_RPW);
        for (int i = 0; i < BASELINE_UPDATES; i++) {
            scorer.update(calmFeatures);
        }

        double baselineMeanRpw = CALM_RPW;
        double baselineStdRpw = MIN_STD;

        RiskDecision calmProbe = engine.evaluate(request, IDENTITY, calmFeatures, new RequestContext());
        assertThat(calmProbe).isNotNull();
        assertThat(calmProbe.startupGraceActive()).isFalse();

        List<DecayRow> elevated = new ArrayList<>(ELEVATED_SAMPLES);
        RequestFeatures stepFeatures = features(STEP_RPW);
        for (int i = 0; i < ELEVATED_SAMPLES; i++) {
            RiskDecision d = engine.evaluate(request, IDENTITY, stepFeatures, new RequestContext());
            assertThat(d).isNotNull();
            double z = Math.abs((STEP_RPW - baselineMeanRpw) / baselineStdRpw);
            if (i > 0) {
                z = Double.NaN;
            }
            elevated.add(new DecayRow(i + 1, STEP_RPW, z, d.anomalyScore(), d.action()));
        }

        DecayRow firstStep = elevated.get(0);
        DecayRow last = elevated.get(elevated.size() - 1);
        String evidence = formatEvidence(calmProbe, elevated);
        return new StepRun(firstStep, last, elevated, evidence);
    }

    private static void printTable(StepRun run) {
        System.out.println("sample,rpw,zApprox,score,action");
        for (DecayRow row : run.elevated) {
            System.out.printf(Locale.ROOT, "%d,%.0f,%s,%.6f,%s%n",
                row.sample, row.rpw,
                Double.isNaN(row.zApprox) ? "n/a" : String.format(Locale.ROOT, "%.3f", row.zApprox),
                row.anomalyScore, row.action);
        }
        System.out.printf(Locale.ROOT, "summary first=%.6f last=%.6f%n",
            run.first.anomalyScore(), run.last.anomalyScore());
    }

    private static String formatEvidence(RiskDecision calm, List<DecayRow> elevated) {
        DecayRow first = elevated.get(0);
        DecayRow last = elevated.get(elevated.size() - 1);
        return String.format(Locale.ROOT,
            "scenario=%s calmScore=%.6f calmAction=%s firstStepScore=%.6f firstStepAction=%s lastScore=%.6f lastAction=%s",
            SCENARIO_ID, calm.anomalyScore(), calm.action(),
            first.anomalyScore(), first.action(), last.anomalyScore(), last.action());
    }

    private static RequestFeatures features(double requestsPerWindow) {
        return RequestFeatures.builder()
            .identityHash(IDENTITY)
            .endpoint(ENDPOINT)
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(requestsPerWindow)
            .endpointEntropy(0.1)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(42L)
            .ipBucket(7)
            .build();
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

    private record StepRun(DecayRow first, DecayRow last, List<DecayRow> elevated, String evidence) {}

    private record DecayRow(int sample, double rpw, double zApprox, double anomalyScore, EnforcementAction action) {}

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
