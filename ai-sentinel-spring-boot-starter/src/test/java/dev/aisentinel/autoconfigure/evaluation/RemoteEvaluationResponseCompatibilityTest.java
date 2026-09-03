package dev.aisentinel.autoconfigure.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aisentinel.autoconfigure.web.RemoteEvaluationController;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.metrics.RemoteEvaluationOutcome;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Remote EvaluationResponse parsing: unknown additive fields ignored; malformed known fields fail open.
 */
class RemoteEvaluationResponseCompatibilityTest {

    private static final String API_KEY = "compat-remote-key";

    @Test
    void unknownEvaluationResponseFieldsAreIgnoredWithStrictHostMapper() throws Exception {
        ObjectMapper strictMapper = new ObjectMapper().findAndRegisterModules();
        strictMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            byte[] resp = """
                {"contractVersion":1,"correlationId":"corr-unknown","action":"ALLOW","evaluationStatuses":["COMPLETE"],"anomalyScore":0.12,"policyScore":0.12,"startupGraceActive":false,"proceed":true,"endpoint":"/api/compat","factors":[],"futureOptionalField":"additive-value"}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        try {
            List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
            RemoteEvaluationClient client = new RemoteEvaluationClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                RemoteEvaluationController.PATH,
                API_KEY,
                Duration.ofMillis(200),
                Duration.ofSeconds(2),
                strictMapper,
                recording(outcomes));

            EvaluationResponse response = client.evaluate(request("corr-unknown"));

            assertThat(response.evaluationStatuses())
                .doesNotContain(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            assertThat(outcomes).containsExactly(RemoteEvaluationOutcome.SUCCESS.name());
            assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(response.proceed()).isTrue();
            assertThat(response.correlationId()).isEqualTo("corr-unknown");
            assertThat(response.anomalyScore()).isEqualTo(0.12);
            assertThat(response.policyScore()).isEqualTo(0.12);
            assertThat(response.endpoint()).isEqualTo("/api/compat");
            assertThat(strictMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void malformedKnownEvaluationResponseFieldsStillFailOpen() throws Exception {
        ObjectMapper strictMapper = new ObjectMapper().findAndRegisterModules();
        strictMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            // Known field present but malformed JSON structure → parse failure → fail-open.
            byte[] resp = """
                {"contractVersion":1,"correlationId":"corr-bad","action":"ALLOW","proceed":true,"evaluationStatuses":"COMPLETE"}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        try {
            List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
            RemoteEvaluationClient client = new RemoteEvaluationClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                RemoteEvaluationController.PATH,
                API_KEY,
                Duration.ofMillis(200),
                Duration.ofSeconds(2),
                strictMapper,
                recording(outcomes));

            EvaluationResponse response = client.evaluate(request("corr-bad"));

            assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(response.proceed()).isTrue();
            assertThat(response.anomalyScore()).isNull();
            assertThat(response.policyScore()).isNull();
            assertThat(response.factors()).isEmpty();
            assertThat(response.advice()).isNull();
            assertThat(response.evaluationStatuses())
                .contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            assertThat(outcomes).contains(RemoteEvaluationOutcome.MALFORMED_RESPONSE.name());
            assertThat(strictMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static EvaluationRequest request(String correlationId) {
        return EvaluationRequest.builder()
            .correlationId(correlationId)
            .identityKey("id")
            .path("/api/compat")
            .build();
    }

    private static SentinelMetrics recording(List<String> outcomes) {
        return new SentinelMetrics() {
            @Override
            public void recordRemoteEvaluationOutcome(String outcome) {
                outcomes.add(outcome);
            }
        };
    }
}
