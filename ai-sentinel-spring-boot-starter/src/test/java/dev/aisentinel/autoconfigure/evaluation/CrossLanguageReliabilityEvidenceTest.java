package dev.aisentinel.autoconfigure.evaluation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aisentinel.autoconfigure.web.RemoteEvaluationController;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.contract.EvaluationResponseValidator;
import dev.aisentinel.core.decision.EvaluationStatus;
import dev.aisentinel.core.metrics.RemoteEvaluationOutcome;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.EnforcementAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared-fixture and client reliability evidence for the remote evaluation contract.
 * Repository-controlled only — not production interoperability certification.
 */
class CrossLanguageReliabilityEvidenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .findAndRegisterModules()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String API_KEY = "reliability-evidence-key";

    @ParameterizedTest
    @ValueSource(strings = {
        "allow.json",
        "allow-minimal.json",
        "allow-additive-unknown.json",
        "monitor.json",
        "block.json",
        "remote-failure.json"
    })
    void sharedResponseFixturesDeserializeAndValidate(String fileName) throws Exception {
        Path fixture = repoRoot().resolve("dotnet/fixtures/responses").resolve(fileName);
        assertThat(fixture).exists();
        EvaluationResponse response = MAPPER.readValue(Files.readString(fixture), EvaluationResponse.class);
        EvaluationResponseValidator.validate(response, response.correlationId());
    }

    @Test
    void additiveUnknownFieldsAreIgnoredEvenWithStrictCallerMapper() throws Exception {
        Path fixture = repoRoot().resolve("dotnet/fixtures/responses/allow-additive-unknown.json");
        ObjectMapper strict = new ObjectMapper().findAndRegisterModules();
        strict.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] body = Files.readAllBytes(fixture);
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
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
                strict,
                recording(outcomes));

            EvaluationResponse response = client.evaluate(request("fixture-allow-additive"));
            assertThat(response.evaluationStatuses())
                .doesNotContain(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(response.proceed()).isTrue();
            assertThat(outcomes).containsExactly(RemoteEvaluationOutcome.SUCCESS.name());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void authRejectedIgnoresSuccessShapedBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            // Always reject auth, even if body would look like a BLOCK decision.
            byte[] body = """
                {"contractVersion":1,"correlationId":"auth-body","action":"BLOCK","evaluationStatuses":["COMPLETE"],"proceed":false,"endpoint":"/","anomalyScore":0.99,"policyScore":0.99}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
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
                "any-key",
                Duration.ofMillis(200),
                Duration.ofSeconds(2),
                MAPPER,
                recording(outcomes));

            EvaluationResponse response = client.evaluate(request("auth-body"));
            assertThat(response.evaluationStatuses())
                .contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(response.proceed()).isTrue();
            assertThat(response.anomalyScore()).isNull();
            assertThat(response.policyScore()).isNull();
            assertThat(outcomes).contains(RemoteEvaluationOutcome.AUTH_REJECTED.name());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void unsupportedContractVersionIsRemoteFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            byte[] body = """
                {"contractVersion":99,"correlationId":"ver-skew","action":"ALLOW","evaluationStatuses":["COMPLETE"],"proceed":true,"endpoint":"/"}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
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
                MAPPER,
                recording(outcomes));

            EvaluationResponse response = client.evaluate(request("ver-skew"));
            assertThat(response.evaluationStatuses())
                .contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(outcomes).contains(RemoteEvaluationOutcome.VERSION_MISMATCH.name());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void numericActionIsRemoteFailureNotOrdinalAllow() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            byte[] body = """
                {"contractVersion":1,"correlationId":"numeric-action","action":0,"evaluationStatuses":["COMPLETE"],"proceed":true,"endpoint":"/"}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
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
                MAPPER,
                recording(outcomes));

            EvaluationResponse response = client.evaluate(request("numeric-action"));
            assertThat(response.evaluationStatuses())
                .contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(response.proceed()).isTrue();
            assertThat(response.anomalyScore()).isNull();
            assertThat(response.policyScore()).isNull();
            assertThat(outcomes).contains(RemoteEvaluationOutcome.MALFORMED_RESPONSE.name());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"contractVersion\":1,\"correlationId\":\"invalid-score\",\"action\":\"ALLOW\",\"evaluationStatuses\":[\"COMPLETE\"],\"proceed\":true,\"endpoint\":\"/\",\"anomalyScore\":\"NaN\"}",
        "{\"contractVersion\":1,\"correlationId\":\"invalid-score\",\"action\":\"ALLOW\",\"evaluationStatuses\":[\"COMPLETE\"],\"proceed\":true,\"endpoint\":\"/\",\"policyScore\":true}"
    })
    void invalidNumericScoreFieldsAreRemoteFailure(String body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
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
                MAPPER,
                recording(outcomes));

            EvaluationResponse response = client.evaluate(request("invalid-score"));
            assertThat(response.evaluationStatuses())
                .contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            assertThat(response.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(response.proceed()).isTrue();
            assertThat(response.anomalyScore()).isNull();
            assertThat(response.policyScore()).isNull();
            assertThat(outcomes).contains(RemoteEvaluationOutcome.MALFORMED_RESPONSE.name());
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static EvaluationRequest request(String correlationId) {
        return EvaluationRequest.builder()
            .correlationId(correlationId)
            .identityKey("id")
            .path("/api/hello")
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

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("dotnet/AI.Sentinel.sln"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }
}
