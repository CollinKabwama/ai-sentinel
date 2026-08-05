package dev.aisentinel.core.regression;

import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.store.BaselineStore;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression (no Spring, no servlet): real feature extractor → decision engine → enforcement
 * for every {@link EnforcementAction}, plus cold-start warmup and fail-open scoring.
 */
class EndToEndPipelineRegressionTest {

    private static final class FixedScorer implements AnomalyScorer {
        private final AtomicReference<Double> value = new AtomicReference<>(0.0);

        void set(double v) {
            value.set(v);
        }

        @Override
        public double score(RequestFeatures features) {
            return value.get();
        }

        @Override
        public void update(RequestFeatures features) {
        }
    }

    private static final class RecordingResponse implements EnforcementResponse {
        int status = -1;
        String contentType;
        final StringBuilder body = new StringBuilder();

        @Override
        public void setStatus(int statusCode) {
            this.status = statusCode;
        }

        @Override
        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        @Override
        public void writeBody(String body) {
            this.body.append(body);
        }
    }

    private FixedScorer scorer;
    private CompositeEnforcementHandler enforcement;
    private SentinelPipeline pipeline;
    private final List<String> telemetry = new ArrayList<>();

    @BeforeEach
    void setUp() {
        scorer = new FixedScorer();
        TelemetryEmitter tel = event -> telemetry.add(event.type());
        enforcement = new CompositeEnforcementHandler(429, 60_000L, 1000.0, tel, 1000, 60_000L);
        pipeline = new SentinelPipeline(
            new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(5), 10_000)),
            scorer,
            new ThresholdPolicyEngine(),
            enforcement,
            tel,
            StartupGrace.NEVER,
            SentinelMetrics.NOOP
        );
    }

    private MapHttpRequestView req(String uri) {
        return new MapHttpRequestView().requestUri(uri).method("GET").remoteAddr("203.0.113.10");
    }

    @Test
    void allow_lowScoreProceedsWithoutWritingResponse() {
        scorer.set(0.0);
        RecordingResponse response = new RecordingResponse();
        boolean proceed = pipeline.process(req("/api/hello"), response, "id-allow");
        assertThat(proceed).isTrue();
        assertThat(response.status).isEqualTo(-1);
        assertThat(telemetry).contains("ThreatScored");
    }

    @Test
    void monitor_moderateScoreProceeds() {
        scorer.set(0.3);
        RecordingResponse response = new RecordingResponse();
        boolean proceed = pipeline.process(req("/api/hello"), response, "id-monitor");
        assertThat(proceed).isTrue();
        assertThat(response.status).isEqualTo(-1);
        assertThat(telemetry).contains("PolicyActionApplied");
    }

    @Test
    void throttle_elevatedScoreAllowsWhenBudgetAvailable() {
        scorer.set(0.5);
        RecordingResponse response = new RecordingResponse();
        boolean proceed = pipeline.process(req("/api/hello"), response, "id-throttle");
        assertThat(proceed).isTrue();
        assertThat(response.status).isEqualTo(-1);
    }

    @Test
    void throttle_exhaustedBudgetWrites429() {
        scorer.set(0.5);
        CompositeEnforcementHandler tight = new CompositeEnforcementHandler(
            429, 60_000L, 1.0, e -> {}, 1000, 60_000L);
        SentinelPipeline tightPipeline = new SentinelPipeline(
            new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(5), 10_000)),
            scorer,
            new ThresholdPolicyEngine(),
            tight,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP
        );
        assertThat(tightPipeline.process(req("/api/hello"), new RecordingResponse(), "id-throttle-exhaust")).isTrue();
        RecordingResponse blocked = new RecordingResponse();
        boolean proceed = tightPipeline.process(req("/api/hello"), blocked, "id-throttle-exhaust");
        assertThat(proceed).isFalse();
        assertThat(blocked.status).isEqualTo(429);
        assertThat(blocked.body.toString()).contains("Too Many Requests");
    }

    @Test
    void block_highScoreWritesBlockStatus() {
        scorer.set(0.7);
        RecordingResponse response = new RecordingResponse();
        boolean proceed = pipeline.process(req("/api/secret"), response, "id-block");
        assertThat(proceed).isFalse();
        assertThat(response.status).isEqualTo(429);
        assertThat(response.body.toString()).isNotBlank();
    }

    @Test
    void quarantine_criticalScoreQuarantinesAndSubsequentRequestsStayBlocked() {
        scorer.set(0.9);
        RecordingResponse first = new RecordingResponse();
        assertThat(pipeline.process(req("/api/admin"), first, "id-quarantine")).isFalse();
        assertThat(first.status).isEqualTo(429);
        assertThat(first.body.toString()).contains("Quarantined");
        assertThat(enforcement.isQuarantined("id-quarantine", "/api/admin")).isTrue();

        scorer.set(0.0);
        RecordingResponse second = new RecordingResponse();
        assertThat(pipeline.process(req("/api/admin"), second, "id-quarantine")).isFalse();
        assertThat(second.status).isEqualTo(429);
    }

    @Test
    void coldStart_featureExtractionProducesNumericFeaturesWithoutBaselineHistory() {
        scorer.set(0.0);
        RecordingResponse response = new RecordingResponse();
        boolean proceed = pipeline.process(
            req("/api/new-user").header("Content-Length", "12").parameter("q", "1"),
            response,
            "brand-new-identity"
        );
        assertThat(proceed).isTrue();
        assertThat(telemetry).contains("ThreatScored");
    }

    @Test
    void scoringFailure_failOpenAllowsRequest() {
        AnomalyScorer exploding = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                throw new IllegalStateException("model down");
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        SentinelPipeline failOpen = new SentinelPipeline(
            new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(5), 10_000)),
            exploding,
            new ThresholdPolicyEngine(),
            enforcement,
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP
        );
        RecordingResponse response = new RecordingResponse();
        assertThat(failOpen.process(req("/api/hello"), response, "id-fail")).isTrue();
        assertThat(response.status).isEqualTo(-1);
    }
}
