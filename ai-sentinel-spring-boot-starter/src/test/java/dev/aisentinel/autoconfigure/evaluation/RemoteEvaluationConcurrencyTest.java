package dev.aisentinel.autoconfigure.evaluation;

import dev.aisentinel.autoconfigure.web.RemoteEvaluationController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.contract.LocalEvaluationBridge;
import dev.aisentinel.core.contract.LocalEvaluationExecutor;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
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

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteEvaluationConcurrencyTest {

    private static final String API_KEY = "concurrency-key-xyz";

    private HttpServer server;
    private int port;
    private ObjectMapper mapper;
    private LocalEvaluationExecutor local;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        AnomalyScorer scorer = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                return 0.05;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        local = new LocalEvaluationExecutor(new LocalEvaluationBridge(fixedExtractor(), engine(scorer)));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            String key = exchange.getRequestHeaders().getFirst(RemoteEvaluationConstants.API_KEY_HEADER);
            if (!ApiKeyAuthenticator.matches(API_KEY, key)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            EvaluationRequest request = mapper.readValue(exchange.getRequestBody(), EvaluationRequest.class);
            EvaluationResponse response = local.evaluate(request);
            byte[] body = mapper.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
            exchange.close();
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
    void concurrentClientsKeepCorrelationIsolation() throws Exception {
        RemoteEvaluationClient client = new RemoteEvaluationClient(
            "http://127.0.0.1:" + port,
            RemoteEvaluationController.PATH,
            API_KEY,
            Duration.ofMillis(500),
            Duration.ofSeconds(2),
            mapper,
            SentinelMetrics.NOOP);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<EvaluationResponse>> futures = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String corr = "corr-" + i;
            futures.add(pool.submit(() -> client.evaluate(EvaluationRequest.builder()
                .correlationId(corr)
                .identityKey("id-" + corr)
                .path("/api/hello")
                .build())));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < futures.size(); i++) {
            EvaluationResponse response = futures.get(i).get();
            assertThat(response.correlationId()).isEqualTo("corr-" + i);
            assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
        }
    }

    private static FeatureExtractor fixedExtractor() {
        return (request, identityHash, ctx) -> RequestFeatures.builder()
            .identityHash(identityHash)
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
}
