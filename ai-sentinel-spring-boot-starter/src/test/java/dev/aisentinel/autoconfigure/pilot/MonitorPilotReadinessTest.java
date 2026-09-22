package dev.aisentinel.autoconfigure.pilot;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.autoconfigure.web.SentinelFilter;
import dev.aisentinel.autoconfigure.web.ServletHttpRequestView;
import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.enforcement.DiscardingEnforcementResponse;
import dev.aisentinel.core.enforcement.MonitorOnlyEnforcementHandler;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.pilot.LocalPilotEvidenceSession;
import dev.aisentinel.core.pilot.PilotConfigSnapshot;
import dev.aisentinel.core.pilot.PilotObservation;
import dev.aisentinel.core.pilot.PilotObservationRecorder;
import dev.aisentinel.core.pilot.PilotPseudonymizer;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.store.BaselineStore;
import dev.aisentinel.distributed.training.NoopTrainingCandidatePublisher;
import dev.aisentinel.distributed.training.TrainingFingerprintHashes;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Synthetic MONITOR pilot tests — no real organization traffic.
 */
class MonitorPilotReadinessTest {

    private static final String SECRET = "pilot-hmac-secret!!";

    @TempDir
    Path temp;

    @ParameterizedTest
    @CsvSource({
        "0.0,ALLOW",
        "0.3,MONITOR",
        "0.5,THROTTLE",
        "0.7,BLOCK",
        "0.9,QUARANTINE"
    })
    void monitorMode_allProposedActionsContinueChainWithoutMutation(double score, String expectedAction)
        throws Exception {
        Path dir = temp.resolve("matrix-" + expectedAction);
        LocalPilotEvidenceSession session = openSession(dir, "matrix-" + expectedAction);
        FixedScorer scorer = new FixedScorer(score);
        SentinelPipeline pipeline = monitorPipeline(scorer, session);
        SentinelProperties props = monitorProps();
        SentinelFilter filter = new SentinelFilter(pipeline, props, SentinelMetrics.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/secure");
        request.setRemoteAddr("203.0.113.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.getWriter().write("app-ok");
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        AtomicInteger appStatus = new AtomicInteger();

        FilterChain chain = (req, res) -> {
            chainCalled.set(true);
            MockHttpServletResponse http = (MockHttpServletResponse) res;
            appStatus.set(http.getStatus());
            http.getWriter().write("-continued");
        };

        filter.doFilter(request, response, chain);
        session.finalizeSession();

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).doesNotContainIgnoringCase("quarantine");
        assertThat(response.getContentAsString()).doesNotContainIgnoringCase("blocked");

        String observations = Files.readString(dir.resolve(LocalPilotEvidenceSession.OBSERVATIONS_FILE));
        assertThat(observations).contains("\"riskDerivedAction\":\"" + expectedAction + "\"");
        assertThat(observations).contains("\"enforcementApplied\":false");
        assertThat(observations).contains("\"requestOutcome\":\"CONTINUED\"");
        assertThat(observations).contains("\"runtimeMode\":\"MONITOR\"");
    }

    @Test
    void privacyMarkersNeverPersistInPilotArtifacts() throws Exception {
        Path dir = temp.resolve("privacy");
        LocalPilotEvidenceSession session = openSession(dir, "privacy-session");
        FixedScorer scorer = new FixedScorer(0.7);
        SentinelPipeline pipeline = monitorPipeline(scorer, session);
        SentinelProperties props = monitorProps();
        SentinelFilter filter = new SentinelFilter(pipeline, props, SentinelMetrics.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/accounts/alice@example.com/reset/SECRET_TOKEN_VALUE");
        request.setRemoteAddr("198.51.100.123");
        request.addHeader("Authorization", "Bearer PILOT_SECRET_MARKER_AUTH");
        request.addHeader("Cookie", "PILOT_SECRET_MARKER_COOKIE");
        request.setQueryString("token=PILOT_SECRET_MARKER_BODY");
        request.setParameter("password", "PILOT_SECRET_MARKER_BODY");
        request.setContent("PILOT_SECRET_MARKER_BODY".getBytes(StandardCharsets.UTF_8));
        // Principal path would hash username; set remote for IP path and also stamp a raw marker via header noise.
        request.addHeader("X-User", "pilot-user@example.invalid");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        session.finalizeSession();

        String all = readAllArtifacts(dir);
        assertThat(all).doesNotContain("PILOT_SECRET_MARKER_AUTH");
        assertThat(all).doesNotContain("PILOT_SECRET_MARKER_COOKIE");
        assertThat(all).doesNotContain("PILOT_SECRET_MARKER_BODY");
        assertThat(all).doesNotContain("pilot-user@example.invalid");
        assertThat(all).doesNotContain("alice@example.com");
        assertThat(all).doesNotContain("SECRET_TOKEN_VALUE");
        assertThat(all).doesNotContain("198.51.100.123");
        assertThat(all).doesNotContain(SECRET);
        assertThat(all).doesNotContain("/api/accounts/");
        assertThat(all).containsPattern("\"endpointKey\":\"[a-f0-9]{64}\"");
        assertThat(all).contains("\"pseudonymousIdentity\":");
    }

    @Test
    void sinkWriteFailure_doesNotDenyRequest() throws Exception {
        AtomicInteger records = new AtomicInteger();
        PilotObservationRecorder failing = (identity, decision, latency, proceeded) -> {
            records.incrementAndGet();
            throw new RuntimeException("synthetic sink failure");
        };
        FixedScorer scorer = new FixedScorer(0.95);
        SentinelPipeline pipeline = monitorPipeline(scorer, failing);
        SentinelProperties props = monitorProps();
        SentinelFilter filter = new SentinelFilter(pipeline, props, SentinelMetrics.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/secure");
        request.setRemoteAddr("203.0.113.50");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(records.get()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void featureExtractionFailure_continuesChain() throws Exception {
        FeatureExtractor exploding = (request, identityHash, ctx) -> {
            throw new IllegalStateException("synthetic feature failure");
        };
        Path dir = temp.resolve("fe-fail");
        LocalPilotEvidenceSession session = openSession(dir, "fe-fail");
        SentinelPipeline pipeline = new SentinelPipeline(
            exploding,
            new FixedScorer(0.9),
            null,
            new ThresholdPolicyEngine(),
            new MonitorOnlyEnforcementHandler(
                new CompositeEnforcementHandler(429, 60_000L, 1000.0, e -> {}, 1000, 60_000L),
                e -> {}),
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrainingCandidatePublisher.INSTANCE,
            dev.aisentinel.core.enforcement.EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "MONITOR",
            null,
            null,
            null,
            null,
            null,
            EnforcementAction.MONITOR,
            dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            dev.aisentinel.core.baseline.BaselineLifecycle.disabled(),
            dev.aisentinel.core.decision.LastDecisionExplanation.NOOP,
            dev.aisentinel.core.scoring.shadow.ShadowScoringExecutor.disabled(),
            session
        );
        SentinelFilter filter = new SentinelFilter(pipeline, monitorProps(), SentinelMetrics.NOOP);
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        filter.doFilter(new MockHttpServletRequest("GET", "/api/x"), new MockHttpServletResponse(),
            (req, res) -> chainCalled.set(true));
        session.finalizeSession();
        assertThat(chainCalled).isTrue();
    }

    @Test
    void scorerException_continuesChain() throws Exception {
        AnomalyScorer exploding = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                throw new IllegalStateException("synthetic scorer failure");
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        Path dir = temp.resolve("scorer-fail");
        LocalPilotEvidenceSession session = openSession(dir, "scorer-fail");
        SentinelPipeline pipeline = monitorPipeline(exploding, session);
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        new SentinelFilter(pipeline, monitorProps(), SentinelMetrics.NOOP)
            .doFilter(new MockHttpServletRequest("GET", "/api/y"), new MockHttpServletResponse(),
                (req, res) -> chainCalled.set(true));
        session.finalizeSession();
        assertThat(chainCalled).isTrue();
    }

    @Test
    void sinkInactiveOutsideMonitorModeGate() throws Exception {
        Path dir = temp.resolve("off-mode");
        LocalPilotEvidenceSession session = openSession(dir, "off-mode");
        AtomicInteger records = new AtomicInteger();
        PilotObservationRecorder counting = (id, d, l, p) -> {
            records.incrementAndGet();
            session.record(id, d, l, p);
        };
        PilotObservationRecorder gated = new dev.aisentinel.core.pilot.GatedPilotObservationRecorder(
            () -> false,
            counting
        );
        SentinelPipeline pipeline = monitorPipeline(new FixedScorer(0.9), gated);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/z");
        request.setRemoteAddr("203.0.113.9");
        pipeline.process(
            new ServletHttpRequestView(request),
            DiscardingEnforcementResponse.INSTANCE,
            TrainingFingerprintHashes.sha256HexUtf8("203.0.113.9")
        );
        assertThat(records.get()).isEqualTo(0);
        session.finalizeSession();
        String observations = Files.readString(dir.resolve(LocalPilotEvidenceSession.OBSERVATIONS_FILE));
        assertThat(observations).isEmpty();
    }

    @Test
    void configValidation_rejectsEnforceModeAndCustomHandlerAndTrainingPublish() {
        SentinelProperties props = monitorProps();
        props.getPilot().setEnabled(true);
        props.getPilot().setOutputDirectory(temp.resolve("cfg").toString());
        props.getPilot().setPseudonymizationSecret(SECRET);

        props.setMode(SentinelProperties.Mode.ENFORCE);
        assertThatThrownBy(() -> PilotEvidenceConfiguration.validatePilotSafeConfiguration(
            props, new MonitorOnlyEnforcementHandler(
                new CompositeEnforcementHandler(429, 1, 1, e -> {}, 1000, 1000), e -> {})))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MONITOR");

        props.setMode(SentinelProperties.Mode.MONITOR);
        props.getDistributed().setTrainingPublishEnabled(true);
        assertThatThrownBy(() -> PilotEvidenceConfiguration.validatePilotSafeConfiguration(
            props, new MonitorOnlyEnforcementHandler(
                new CompositeEnforcementHandler(429, 1, 1, e -> {}, 1000, 1000), e -> {})))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("training-publish");

        props.getDistributed().setTrainingPublishEnabled(false);
        assertThatThrownBy(() -> PilotEvidenceConfiguration.validatePilotSafeConfiguration(
            props, new CompositeEnforcementHandler(429, 1, 1, e -> {}, 1000, 1000)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MonitorOnlyEnforcementHandler");
    }

    @Test
    void hmacPseudonymStableWithinSessionAndSecretNeverPersisted() throws Exception {
        Path dir = temp.resolve("hmac");
        LocalPilotEvidenceSession session = openSession(dir, "hmac-session");
        FixedScorer scorer = new FixedScorer(0.1);
        SentinelPipeline pipeline = monitorPipeline(scorer, session);
        SentinelFilter filter = new SentinelFilter(pipeline, monitorProps(), SentinelMetrics.NOOP);

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/hello");
            request.setRemoteAddr("203.0.113.77");
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        }
        session.finalizeSession();

        String expected = new PilotPseudonymizer(SECRET)
            .pseudonymize(TrainingFingerprintHashes.sha256HexUtf8("203.0.113.77"));
        String observations = Files.readString(dir.resolve(LocalPilotEvidenceSession.OBSERVATIONS_FILE));
        assertThat(observations).contains(expected);
        assertThat(readAllArtifacts(dir)).doesNotContain(SECRET);
    }

    @Test
    void pilotSecretNotIncludedInGeneratedConfigString() {
        SentinelProperties.Pilot pilot = new SentinelProperties.Pilot();
        pilot.setEnabled(true);
        pilot.setPseudonymizationSecret(SECRET);

        assertThat(pilot.toString()).doesNotContain(SECRET);
    }

    private LocalPilotEvidenceSession openSession(Path dir, String sessionId) throws Exception {
        PilotConfigSnapshot config = new PilotConfigSnapshot(
            PilotObservation.RUNTIME_MODE_MONITOR,
            "ALLOW_OR_MONITOR",
            "composite",
            "0.4.0",
            "0.4.0",
            "1",
            PilotObservation.SCHEMA_VERSION,
            PilotObservation.EVIDENCE_CLASS,
            "threshold-policy",
            0.2,
            0.4,
            0.6,
            0.8
        );
        return new LocalPilotEvidenceSession(dir, sessionId, config, new PilotPseudonymizer(SECRET));
    }

    private SentinelPipeline monitorPipeline(AnomalyScorer scorer, PilotObservationRecorder recorder) {
        return new SentinelPipeline(
            new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(5), 10_000)),
            scorer,
            null,
            new ThresholdPolicyEngine(),
            new MonitorOnlyEnforcementHandler(
                new CompositeEnforcementHandler(429, 60_000L, 1000.0, e -> {}, 1000, 60_000L),
                e -> {}),
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrainingCandidatePublisher.INSTANCE,
            dev.aisentinel.core.enforcement.EnforcementScope.IDENTITY_ENDPOINT,
            "default",
            "",
            "MONITOR",
            null,
            null,
            null,
            null,
            null,
            EnforcementAction.MONITOR,
            dev.aisentinel.core.baseline.ConfigurableBaselineUpdatePolicy.allowOrMonitor(),
            dev.aisentinel.core.baseline.BaselineLifecycle.disabled(),
            dev.aisentinel.core.decision.LastDecisionExplanation.NOOP,
            dev.aisentinel.core.scoring.shadow.ShadowScoringExecutor.disabled(),
            recorder
        );
    }

    private static SentinelProperties monitorProps() {
        SentinelProperties props = new SentinelProperties();
        props.setEnabled(true);
        props.setMode(SentinelProperties.Mode.MONITOR);
        props.setExcludePaths(java.util.List.of("/actuator/**"));
        return props;
    }

    private static String readAllArtifacts(Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Path p : Files.walk(dir).filter(Files::isRegularFile).toList()) {
            sb.append(Files.readString(p));
        }
        return sb.toString();
    }

    private static final class FixedScorer implements AnomalyScorer {
        private final AtomicReference<Double> value;

        FixedScorer(double v) {
            this.value = new AtomicReference<>(v);
        }

        @Override
        public double score(RequestFeatures features) {
            return value.get();
        }

        @Override
        public void update(RequestFeatures features) {
        }
    }
}
