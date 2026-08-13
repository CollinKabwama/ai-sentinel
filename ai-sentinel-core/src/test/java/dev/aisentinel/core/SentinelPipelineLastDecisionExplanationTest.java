package dev.aisentinel.core;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.decision.LastDecisionExplanation;
import dev.aisentinel.core.enforcement.DiscardingEnforcementResponse;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import dev.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fidelity: <strong>unit</strong> — pipeline records a safe last-decision explanation snapshot.
 */
class SentinelPipelineLastDecisionExplanationTest {

    @Test
    void processRecordsLastDecisionWithoutSensitiveIdentityFields() {
        LastDecisionExplanation holder = new LastDecisionExplanation();
        StatisticalScorer statistical = new StatisticalScorer(100, 60_000L, 999, 0.85);
        CompositeScorer composite = new CompositeScorer();
        composite.addScorer(statistical, 1.0);

        FeatureExtractor extractor = (request, identityHash, ctx) -> RequestFeatures.builder()
            .identityHash(identityHash)
            .endpoint("/secret/path")
            .timestampMillis(0)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .tokenAgeSeconds(60)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();

        EnforcementHandler handler = (action, request, response, identityHash, endpoint) -> true;
        TelemetryEmitter telemetry = event -> { };

        SentinelPipeline pipeline = new SentinelPipeline(
            extractor,
            composite,
            composite,
            new ThresholdPolicyEngine(0.3, 0.4, 0.7, 0.9),
            handler,
            telemetry,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            null,
            EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "ENFORCE",
            NoopIdentityContextResolver.INSTANCE,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopIdentityResponseHook.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR,
            ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            BaselineLifecycle.disabled(),
            holder
        );

        boolean proceed = pipeline.process(
            new MapHttpRequestView().requestUri("/secret/path").method("GET").remoteAddr("127.0.0.1"),
            DiscardingEnforcementResponse.INSTANCE,
            "identity-hash-should-not-leak");
        assertThat(proceed).isTrue();

        LastDecisionExplanation.Snapshot snap = holder.get();
        assertThat(snap).isNotNull();
        assertThat(snap.action()).isNotBlank();
        assertThat(snap.anomalyScore()).isEqualTo(0.85);
        assertThat(snap.statisticalScore()).isEqualTo(0.85);
        assertThat(snap.toString()).doesNotContain("identity-hash-should-not-leak");
        assertThat(snap.toString()).doesNotContain("/secret/path");
    }
}
