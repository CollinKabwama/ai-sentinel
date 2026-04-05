package io.aisentinel.core;

import io.aisentinel.core.enforcement.EnforcementHandler;
import io.aisentinel.core.enforcement.EnforcementScope;
import io.aisentinel.core.feature.FeatureExtractor;
import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.policy.PolicyEngine;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.scoring.CompositeScorer;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import io.aisentinel.distributed.training.TrainingCandidatePublishRequest;
import io.aisentinel.distributed.training.TrainingCandidatePublisher;
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
        PolicyEngine policy = new io.aisentinel.core.policy.ThresholdPolicyEngine();
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
            "ENFORCE"
        );

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        assertThat(pipeline.process(request, response, "h")).isTrue();
        verify(publisher).publish(any(TrainingCandidatePublishRequest.class));
        assertThat(unexpected.get()).isEqualTo(1);
    }
}
