package dev.aisentinel.core.scoring;

import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy;
import dev.aisentinel.core.enforcement.DiscardingEnforcementResponse;
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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pipeline/actuator snapshot access depends on {@link CompositeScoreSnapshotSource}; concrete
 * {@link CompositeScorer} remains a construction convenience that implements the capability.
 */
class CompositeScoreSnapshotSourceDecouplingTest {

    @Test
    void customAnomalyScorerWorksWithoutCompositeScorerIdentity() throws Exception {
        AnomalyScorer custom = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return 0.25;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };

        FeatureExtractor extractor = (request, identityHash, ctx) -> RequestFeatures.builder()
            .identityHash(identityHash)
            .endpoint(request.getRequestURI())
            .timestampMillis(1L)
            .requestsPerWindow(1)
            .endpointEntropy(0)
            .endpointConcentration(0)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(0)
            .ipBucket(0)
            .build();

        // Snapshot source may be null when a custom AnomalyScorer replaces the composite.
        SentinelPipeline pipeline = new SentinelPipeline(
            extractor,
            custom,
            null,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            (action, request, response, identityHash, endpoint) -> true,
            e -> {},
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
            BaselineLifecycle.disabled()
        );

        assertThat(custom).isNotInstanceOf(CompositeScorer.class);
        assertThatCode(() -> pipeline.process(
            new MapHttpRequestView().requestUri("/x").method("GET"),
            DiscardingEnforcementResponse.INSTANCE,
            "id"
        )).doesNotThrowAnyException();
    }

    @Test
    void compositeScorerExposesSnapshotThroughCapabilityInterface() {
        CompositeScorer composite = new CompositeScorer();
        composite.addScorer(new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return 0.3;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        }, 1.0);
        CompositeScoreSnapshotSource asCapability = composite;
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/e").timestampMillis(1L)
            .requestsPerWindow(1).endpointEntropy(0).endpointConcentration(0)
            .tokenAgeSeconds(-1).parameterCount(0).payloadSizeBytes(0)
            .headerFingerprintHash(0).ipBucket(0).build();
        assertThat(composite.score(features)).isEqualTo(0.3);
        assertThat(asCapability.getLastCompositeScoreSnapshot()).isNotNull();
        assertThat(asCapability.getLastCompositeScoreSnapshot().composite()).isEqualTo(0.3);
    }
}
