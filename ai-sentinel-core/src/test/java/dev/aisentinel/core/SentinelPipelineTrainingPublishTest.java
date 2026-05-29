package dev.aisentinel.core;

import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.PolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.identity.spi.NoopIdentityContextResolver;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.identity.spi.NoopIdentityResponseHook;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.distributed.training.TrainingCandidatePublishRequest;
import dev.aisentinel.distributed.training.TrainingCandidatePublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SentinelPipelineTrainingPublishTest {

    @Test
    void publisherExceptionDoesNotFailOpenRequestPath() throws Exception {
        FeatureExtractor extractor = mock(FeatureExtractor.class);
        RequestFeatures features = RequestFeatures.builder()
            .identityHash("h").endpoint("/api").timestampMillis(0)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        when(extractor.extract(any(), anyString(), any(RequestContext.class))).thenReturn(features);

        CompositeScorer composite = new CompositeScorer(SentinelMetrics.NOOP);
        PolicyEngine policy = new dev.aisentinel.core.policy.ThresholdPolicyEngine();
        EnforcementHandler handler = mock(EnforcementHandler.class);
        when(handler.isQuarantined(anyString(), anyString())).thenReturn(false);
        when(handler.apply(eq(EnforcementAction.ALLOW), any(), any(), eq("h"), eq("/api"))).thenReturn(true);

        TrainingCandidatePublisher publisher = mock(TrainingCandidatePublisher.class);
        doThrow(new RuntimeException("boom")).when(publisher).publish(any(TrainingCandidatePublishRequest.class));

        AtomicLong unexpected = new AtomicLong();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordTrainingCandidatePublishUnexpectedFailure() {
                unexpected.incrementAndGet();
            }
        };

        SentinelPipeline pipeline = new SentinelPipeline(
            extractor,
            composite,
            composite,
            policy,
            handler,
            mock(TelemetryEmitter.class),
            StartupGrace.NEVER,
            metrics,
            publisher,
            EnforcementScope.IDENTITY_ENDPOINT,
            "tenant1",
            "node-a",
            "ENFORCE",
            NoopIdentityContextResolver.INSTANCE,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopIdentityResponseHook.INSTANCE,
            NoopRequestRiskFusion.INSTANCE
        );

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        assertThat(pipeline.process(request, response, "h")).isTrue();
        verify(publisher).publish(any(TrainingCandidatePublishRequest.class));
        assertThat(unexpected.get()).isEqualTo(1);
    }
}
