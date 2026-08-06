package dev.aisentinel.core.scenario;

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
 * then an abrupt elevated level, then online adaptation decay.
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
 * Finding preserved: the first elevated step saturates to a high action, then decays under
 * continuous online updates (order of ~12–18 samples below THROTTLE in the default fixture).
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
    void suddenStep_detectedThenDecaysUnderOnlineUpdates() {
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 2, 0.4);
        SentinelDecisionEngine engine = newEngine(scorer);
        HttpRequestView request = new MapHttpRequestView().requestUri(ENDPOINT).method("GET");

        RequestFeatures calmFeatures = features(CALM_RPW);
        for (int i = 0; i < BASELINE_UPDATES; i++) {
            scorer.update(calmFeatures);
        }

        // Approximate baseline stats for rpw dimension after constant updates (other dims constant → std≈0).
        double baselineMeanRpw = CALM_RPW;
        double baselineStdRpw = MIN_STD; // constant series → numerical floor in scorer

        RiskDecision calmProbe = engine.evaluate(request, IDENTITY, calmFeatures, new RequestContext());
        assertThat(calmProbe).isNotNull();
        assertThat(calmProbe.startupGraceActive()).isFalse();

        List<DecayRow> elevated = new ArrayList<>(ELEVATED_SAMPLES);
        RequestFeatures stepFeatures = features(STEP_RPW);
        for (int i = 0; i < ELEVATED_SAMPLES; i++) {
            RiskDecision d = engine.evaluate(request, IDENTITY, stepFeatures, new RequestContext());
            assertThat(d).isNotNull();
            double z = Math.abs((STEP_RPW - baselineMeanRpw) / baselineStdRpw);
            // After first update, baseline mean drifts — recompute z approx from known first-step only for row 0;
            // subsequent z are not exposed by StatisticalScorer; leave computed-from-frozen-baseline as upper bound note.
            if (i > 0) {
                z = Double.NaN; // not observable from production scorer after adaptation begins
            }
            elevated.add(new DecayRow(i + 1, STEP_RPW, z, d.anomalyScore(), d.action()));
        }

        DecayRow firstStep = elevated.get(0);

        // Unrelated features stable between calm and step fixtures
        assertThat(stepFeatures.endpointEntropy()).isEqualTo(calmFeatures.endpointEntropy());
        assertThat(stepFeatures.parameterCount()).isEqualTo(calmFeatures.parameterCount());
        assertThat(stepFeatures.payloadSizeBytes()).isEqualTo(calmFeatures.payloadSizeBytes());
        assertThat(stepFeatures.headerFingerprintHash()).isEqualTo(calmFeatures.headerFingerprintHash());
        assertThat(stepFeatures.ipBucket()).isEqualTo(calmFeatures.ipBucket());
        assertThat(stepFeatures.tokenAgeSeconds()).isEqualTo(calmFeatures.tokenAgeSeconds());

        assertThat(STEP_RPW).isGreaterThan(CALM_RPW * 5);
        assertThat(firstStep.anomalyScore())
            .as("first sudden step vs calm (calm=%.6f step=%.6f)", calmProbe.anomalyScore(), firstStep.anomalyScore())
            .isGreaterThan(calmProbe.anomalyScore());

        String evidence = formatEvidence(calmProbe, elevated);

        assertThat(THROTTLE_OR_STRICTER.contains(firstStep.action()))
            .as("first sudden-step action should reach THROTTLE+. %s", evidence)
            .isTrue();

        // Decay: last elevated sample softer than first
        DecayRow last = elevated.get(elevated.size() - 1);
        assertThat(last.anomalyScore())
            .as("adaptation reduces score vs first step. %s", evidence)
            .isLessThan(firstStep.anomalyScore());

        int throttleOrAboveCount = 0;
        Integer firstBelowThrottle = null;
        Integer firstBelowMonitor = null;
        for (DecayRow row : elevated) {
            if (THROTTLE_OR_STRICTER.contains(row.action())) {
                throttleOrAboveCount++;
            } else if (firstBelowThrottle == null) {
                firstBelowThrottle = row.sample;
            }
            if (row.anomalyScore() < 0.2 && firstBelowMonitor == null) {
                firstBelowMonitor = row.sample;
            }
        }

        // Persist decay shape for operators reading surefire / reports
        System.out.println(SCENARIO_ID + " decay table:");
        System.out.println("sample,rpw,zApprox,score,action");
        for (DecayRow row : elevated) {
            System.out.printf(Locale.ROOT, "%d,%.0f,%s,%.6f,%s%n",
                row.sample, row.rpw,
                Double.isNaN(row.zApprox) ? "n/a" : String.format(Locale.ROOT, "%.3f", row.zApprox),
                row.anomalyScore, row.action);
        }
        System.out.printf(Locale.ROOT,
            "summary calmScore=%.6f firstStep=%.6f last=%.6f throttleOrAboveCount=%d firstBelowThrottle=%s firstBelowMonitor=%s%n",
            calmProbe.anomalyScore(), firstStep.anomalyScore(), last.anomalyScore(),
            throttleOrAboveCount, firstBelowThrottle, firstBelowMonitor);

        assertThat(throttleOrAboveCount)
            .as("at least the first elevated sample should be THROTTLE+. %s", evidence)
            .isGreaterThanOrEqualTo(1);
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

    private static SentinelDecisionEngine newEngine(StatisticalScorer scorer) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            NeverQuarantined.INSTANCE,
            NoopTel.INSTANCE,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );
    }

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
