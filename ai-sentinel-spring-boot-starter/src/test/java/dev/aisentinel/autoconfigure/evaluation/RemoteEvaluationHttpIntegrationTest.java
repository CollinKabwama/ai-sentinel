package dev.aisentinel.autoconfigure.evaluation;

import dev.aisentinel.autoconfigure.web.RemoteEvaluationController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aisentinel.core.contract.EvaluationFailureResponses;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.contract.LocalEvaluationBridge;
import dev.aisentinel.core.contract.LocalEvaluationExecutor;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.RemoteEvaluationOutcome;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real HTTP client ↔ in-process server using the Step-8 contract and authoritative engine.
 */
class RemoteEvaluationHttpIntegrationTest {

    private static final String API_KEY = "integration-key-abcdef";

    private HttpServer server;
    private int port;
    private ObjectMapper mapper;
    private LocalEvaluationExecutor localExecutor;
    private AnomalyScorer sharedScorer;
    private AtomicInteger httpHits;
    private List<String> outcomes;

    @BeforeEach
    void setUp() throws IOException {
        mapper = new ObjectMapper().findAndRegisterModules();
        httpHits = new AtomicInteger();
        outcomes = new ArrayList<>();
        sharedScorer = new MutableScorer(0.05);
        localExecutor = new LocalEvaluationExecutor(
            new LocalEvaluationBridge(fixedExtractor("hash-1"), engine(sharedScorer)));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            httpHits.incrementAndGet();
            try {
                String key = exchange.getRequestHeaders().getFirst(RemoteEvaluationConstants.API_KEY_HEADER);
                if (!ApiKeyAuthenticator.matches(API_KEY, key)) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                byte[] reqBytes = exchange.getRequestBody().readAllBytes();
                EvaluationRequest request = mapper.readValue(reqBytes, EvaluationRequest.class);
                EvaluationResponse response = localExecutor.evaluate(request);
                byte[] body = mapper.writeValueAsBytes(response);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            } catch (Exception ex) {
                byte[] err = ("{\"error\":\"" + ex.getClass().getSimpleName() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void localAndRemoteAreSemanticallyEquivalentForAllowMonitorBlockQuarantineInvalid() {
        double[] scores = {0.05, 0.35, 0.7, 0.9, Double.NaN};
        EnforcementAction[] expected = {
            EnforcementAction.ALLOW, EnforcementAction.MONITOR, EnforcementAction.BLOCK,
            EnforcementAction.QUARANTINE, EnforcementAction.ALLOW
        };
        for (int i = 0; i < scores.length; i++) {
            ((MutableScorer) sharedScorer).setScore(scores[i]);
            EvaluationRequest request = EvaluationRequest.builder()
                .correlationId("eq-" + i)
                .identityKey("hash-1")
                .path("/api/hello")
                .build();
            EvaluationResponse local = localExecutor.evaluate(request);
            EvaluationResponse remote = client().evaluate(request);
            assertThat(remote.action()).isEqualTo(local.action()).isEqualTo(expected[i]);
            assertThat(remote.proceed()).isEqualTo(local.proceed());
            assertThat(remote.anomalyScore()).isEqualTo(local.anomalyScore());
            assertThat(remote.policyScore()).isEqualTo(local.policyScore());
            assertThat(remote.evaluationStatuses()).isEqualTo(local.evaluationStatuses());
            assertThat(remote.factors()).hasSameSizeAs(local.factors());
            if (local.advice() == null) {
                assertThat(remote.advice()).isNull();
            } else {
                assertThat(remote.advice().code()).isEqualTo(local.advice().code());
            }
            if (Double.isNaN(scores[i])) {
                assertThat(remote.evaluationStatuses()).contains("INVALID_SCORE");
                assertThat(remote.anomalyScore()).isNull();
            }
        }
    }

    @Test
    void statePersistsAcrossRemoteCalls() {
        MutableScorer scorer = (MutableScorer) sharedScorer;
        scorer.setScore(0.1);
        AtomicInteger updates = scorer.updates;
        EvaluationRequest r1 = EvaluationRequest.builder()
            .correlationId("s1").identityKey("hash-1").path("/api/hello").build();
        client().evaluate(r1);
        int afterFirst = updates.get();
        client().evaluate(EvaluationRequest.builder()
            .correlationId("s2").identityKey("hash-1").path("/api/hello").build());
        assertThat(updates.get()).isGreaterThanOrEqualTo(afterFirst);
        assertThat(httpHits.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void connectionRefusalIsRemoteFailureNotHighRisk() {
        RemoteEvaluationClient bad = new RemoteEvaluationClient(
            "http://127.0.0.1:1",
            RemoteEvaluationController.PATH,
            API_KEY,
            Duration.ofMillis(100),
            Duration.ofMillis(200),
            mapper,
            recordingMetrics());
        EvaluationResponse response = bad.evaluate(EvaluationRequest.builder()
            .correlationId("down").identityKey("id").path("/api").build());
        assertThat(response.evaluationStatuses()).contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
        assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(response.proceed()).isTrue();
        assertThat(outcomes).contains(RemoteEvaluationOutcome.CONNECTION_FAILURE.name());
    }

    @Test
    void authRejectedIsRemoteFailure() {
        RemoteEvaluationClient badKey = new RemoteEvaluationClient(
            "http://127.0.0.1:" + port,
            RemoteEvaluationController.PATH,
            "wrong-key",
            Duration.ofMillis(500),
            Duration.ofSeconds(2),
            mapper,
            recordingMetrics());
        EvaluationResponse response = badKey.evaluate(EvaluationRequest.builder()
            .correlationId("auth").identityKey("id").path("/api").build());
        assertThat(response.evaluationStatuses()).contains("REMOTE_EVALUATION_FAILURE");
        assertThat(outcomes).contains(RemoteEvaluationOutcome.AUTH_REJECTED.name());
    }

    @Test
    void malformedResponseIsRemoteFailure() throws IOException {
        HttpServer weird = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        weird.createContext(RemoteEvaluationController.PATH, exchange -> {
            byte[] body = "{\"action\":\"NOT_A_REAL_ACTION\",\"contractVersion\":1,\"correlationId\":\"x\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        weird.start();
        try {
            RemoteEvaluationClient client = new RemoteEvaluationClient(
                "http://127.0.0.1:" + weird.getAddress().getPort(),
                RemoteEvaluationController.PATH,
                API_KEY,
                Duration.ofMillis(500),
                Duration.ofSeconds(2),
                mapper,
                recordingMetrics());
            // Auth not checked by weird server; client still sends key.
            EvaluationResponse response = client.evaluate(EvaluationRequest.builder()
                .correlationId("x").identityKey("id").path("/api").build());
            assertThat(response.evaluationStatuses()).contains("REMOTE_EVALUATION_FAILURE");
            assertThat(outcomes).anyMatch(o ->
                o.equals(RemoteEvaluationOutcome.MALFORMED_RESPONSE.name())
                    || o.equals(RemoteEvaluationOutcome.UNEXPECTED.name())
                    || o.equals(RemoteEvaluationOutcome.SERIALIZATION_FAILURE.name()));
        } finally {
            weird.stop(0);
        }
    }

    @Test
    void noAutomaticRetryOnPost() {
        client().evaluate(EvaluationRequest.builder()
            .correlationId("once").identityKey("hash-1").path("/api/hello").build());
        assertThat(httpHits.get()).isEqualTo(1);
    }

    @Test
    void localFallbackUsesLocalResult() {
        RemoteEvaluationExecutor remote = new RemoteEvaluationExecutor(new RemoteEvaluationClient(
            "http://127.0.0.1:1",
            RemoteEvaluationController.PATH,
            API_KEY,
            Duration.ofMillis(100),
            Duration.ofMillis(200),
            mapper,
            SentinelMetrics.NOOP));
        AtomicInteger fallbacks = new AtomicInteger();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordRemoteLocalFallback() {
                fallbacks.incrementAndGet();
            }
        };
        EvaluationResponse response = new RemoteWithLocalFallbackExecutor(remote, localExecutor, metrics)
            .evaluate(EvaluationRequest.builder()
                .correlationId("fb").identityKey("hash-1").path("/api/hello").build());
        assertThat(response.evaluationStatuses()).doesNotContain("REMOTE_EVALUATION_FAILURE");
        assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
        assertThat(fallbacks.get()).isEqualTo(1);
    }

    @Test
    void authorizationCookieNotRequiredOnWire() throws Exception {
        AtomicInteger sawAuth = new AtomicInteger();
        AtomicInteger sawCookie = new AtomicInteger();
        HttpServer probe = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        probe.createContext(RemoteEvaluationController.PATH, exchange -> {
            if (exchange.getRequestHeaders().containsKey("Authorization")) {
                sawAuth.incrementAndGet();
            }
            if (exchange.getRequestHeaders().containsKey("Cookie")) {
                sawCookie.incrementAndGet();
            }
            EvaluationResponse ok = EvaluationFailureResponses.remoteFailure("n/a");
            // Return a valid ALLOW-looking success for this probe using local executor shape:
            ok = localExecutor.evaluate(EvaluationRequest.builder()
                .correlationId("probe").identityKey("hash-1").path("/api/hello").build());
            byte[] body = mapper.writeValueAsBytes(ok);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
        });
        probe.start();
        try {
            new RemoteEvaluationClient(
                "http://127.0.0.1:" + probe.getAddress().getPort(),
                RemoteEvaluationController.PATH,
                API_KEY,
                Duration.ofMillis(500),
                Duration.ofSeconds(1),
                mapper,
                SentinelMetrics.NOOP)
                .evaluate(EvaluationRequest.builder()
                    .correlationId("probe").identityKey("hash-1").path("/api/hello").build());
            assertThat(sawAuth.get()).isZero();
            assertThat(sawCookie.get()).isZero();
        } finally {
            probe.stop(0);
        }
    }

    private RemoteEvaluationClient client() {
        return new RemoteEvaluationClient(
            "http://127.0.0.1:" + port,
            RemoteEvaluationController.PATH,
            API_KEY,
            Duration.ofMillis(500),
            Duration.ofSeconds(2),
            mapper,
            recordingMetrics());
    }

    private SentinelMetrics recordingMetrics() {
        return new SentinelMetrics() {
            @Override
            public void recordRemoteEvaluationOutcome(String outcome) {
                outcomes.add(outcome);
            }
        };
    }

    private static FeatureExtractor fixedExtractor(String identity) {
        return (request, identityHash, ctx) -> RequestFeatures.builder()
            .identityHash(identity)
            .endpoint(request.getRequestURI())
            .timestampMillis(1L)
            .requestsPerWindow(1)
            .endpointEntropy(0.1)
            .endpointConcentration(0.1)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(1L)
            .ipBucket(1)
            .build();
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            new EnforcementHandler() {
                @Override
                public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                     String identityHash, String endpoint) {
                    return true;
                }

                @Override
                public boolean isQuarantined(String identityHash, String endpoint) {
                    return false;
                }
            },
            (TelemetryEmitter) event -> {
            },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE);
    }

    private static final class MutableScorer implements AnomalyScorer {
        private volatile double score;
        private final AtomicInteger updates = new AtomicInteger();

        private MutableScorer(double score) {
            this.score = score;
        }

        void setScore(double score) {
            this.score = score;
        }

        @Override
        public double score(RequestFeatures features) {
            return score;
        }

        @Override
        public void update(RequestFeatures features) {
            updates.incrementAndGet();
        }
    }
}
