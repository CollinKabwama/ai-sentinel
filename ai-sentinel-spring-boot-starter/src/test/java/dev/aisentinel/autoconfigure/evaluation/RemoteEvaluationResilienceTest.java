package dev.aisentinel.autoconfigure.evaluation;

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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterizes remote evaluation resilience: timeouts, HTTP errors, concurrency, recovery, and no automatic retry.
 * Does not introduce circuit breakers.
 */
class RemoteEvaluationResilienceTest {

    private static final String API_KEY = "char-remote-key";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void successfulEvaluationDoesNotAutomaticallyRetry() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = startAllowServer(hits, 0);
        try {
            RemoteEvaluationClient client = client(server.getAddress().getPort(), Duration.ofMillis(200), Duration.ofSeconds(2));
            EvaluationResponse r = client.evaluate(req("no-retry"));
            assertThat(r.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(hits.get()).isEqualTo(1);
            System.out.printf(Locale.ROOT, "no-retry hits=%d%n", hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void remoteTimeoutFailsOpenWithinConfiguredBound() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = startAllowServer(hits, 1500);
        try {
            Duration readTimeout = Duration.ofMillis(200);
            RemoteEvaluationClient client = client(server.getAddress().getPort(), Duration.ofMillis(100), readTimeout);
            long t0 = System.nanoTime();
            EvaluationResponse r = client.evaluate(req("timeout"));
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertThat(r.evaluationStatuses()).contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            assertThat(r.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(r.proceed()).isTrue();
            assertThat(r.anomalyScore()).isNull();
            // Must not wait for the full 1500ms handler delay; allow generous local OS jitter.
            assertThat(elapsedMs)
                .as("elapsedMs=%d should be near readTimeout not handler delay", elapsedMs)
                .isLessThan(1200);
            System.out.printf(Locale.ROOT, "timeout elapsedMs=%d hits=%d%n", elapsedMs, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpServerErrorsAndRateLimitsFailOpenWithoutFabricatedRisk() throws Exception {
        for (int code : new int[] {500, 429}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(RemoteEvaluationController.PATH, exchange -> {
                exchange.sendResponseHeaders(code, -1);
                exchange.close();
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            try {
                List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
                RemoteEvaluationClient client = new RemoteEvaluationClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    RemoteEvaluationController.PATH,
                    API_KEY,
                    Duration.ofMillis(200),
                    Duration.ofSeconds(1),
                    MAPPER,
                    recording(outcomes));
                EvaluationResponse r = client.evaluate(req("status-" + code));
                assertThat(r.action()).isEqualTo(EnforcementAction.ALLOW);
                assertThat(r.proceed()).isTrue();
                assertThat(r.evaluationStatuses()).contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
                System.out.printf(Locale.ROOT, "HTTP %d outcomes=%s%n", code, outcomes);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void remoteEvaluatorRecoversAfterTemporaryOutage() throws Exception {
        // Connection refused against an unreachable endpoint (fail-open only; separate client).
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
        RemoteEvaluationClient down = new RemoteEvaluationClient(
            "http://127.0.0.1:1",
            RemoteEvaluationController.PATH,
            API_KEY,
            Duration.ofMillis(100),
            Duration.ofMillis(200),
            MAPPER,
            recording(outcomes));
        EvaluationResponse fail = down.evaluate(req("outage"));
        assertThat(fail.evaluationStatuses()).contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());

        // Healthy evaluator with a new client instance after the outage.
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = startAllowServer(hits, 0);
        try {
            RemoteEvaluationClient up = client(server.getAddress().getPort(), Duration.ofMillis(200), Duration.ofSeconds(2));
            EvaluationResponse ok = up.evaluate(req("recovered"));
            assertThat(ok.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(ok.evaluationStatuses()).doesNotContain(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            assertThat(hits.get()).isEqualTo(1);
            System.out.printf(Locale.ROOT, "recovery failThenOk outcomes=%s hits=%d%n", outcomes, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sameClientRecoversAfterRepeatedReadTimeouts() throws Exception {
        // Same RemoteEvaluationClient instance: 100 slow (above read timeout) then flip to healthy.
        AtomicInteger hits = new AtomicInteger();
        AtomicBoolean slowMode = new AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            hits.incrementAndGet();
            if (slowMode.get()) {
                try {
                    Thread.sleep(1_500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            String corr = "ok";
            try {
                EvaluationRequest parsed = MAPPER.readValue(reqBytes, EvaluationRequest.class);
                if (parsed.correlationId() != null) {
                    corr = parsed.correlationId();
                }
            } catch (Exception ignored) {
                // keep default
            }
            byte[] resp = ("""
                {"contractVersion":1,"correlationId":"%s","action":"ALLOW","evaluationStatuses":["COMPLETE"],"proceed":true,"endpoint":"/api/char"}
                """.formatted(corr)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        try {
            Duration readTimeout = Duration.ofMillis(200);
            RemoteEvaluationClient client = client(
                server.getAddress().getPort(), Duration.ofMillis(100), readTimeout);
            int storm = 100;
            for (int i = 0; i < storm; i++) {
                EvaluationResponse r = client.evaluate(req("storm-" + i));
                assertThat(r.proceed()).isTrue();
                assertThat(r.action()).isEqualTo(EnforcementAction.ALLOW);
                assertThat(r.anomalyScore()).isNull();
                assertThat(r.evaluationStatuses()).contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            }
            slowMode.set(false);
            EvaluationResponse recovered = client.evaluate(req("same-client-recovered"));
            assertThat(recovered.evaluationStatuses())
                .doesNotContain(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name());
            assertThat(recovered.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(recovered.proceed()).isTrue();
            System.out.printf(Locale.ROOT,
                "same-client timeout-storm=%d then recover hits=%d%n", storm, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void concurrentHealthyEvaluationsCompleteWithoutStuckRequests() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = startAllowServer(hits, 5);
        int concurrency = 250;
        try {
            RemoteEvaluationClient client = client(server.getAddress().getPort(), Duration.ofMillis(500), Duration.ofSeconds(3));
            ExecutorService pool = Executors.newFixedThreadPool(64);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<EvaluationResponse>> futures = new ArrayList<>();
            long t0 = System.nanoTime();
            for (int i = 0; i < concurrency; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return client.evaluate(req("c-" + idx));
                }));
            }
            start.countDown();
            int allow = 0;
            int failOpen = 0;
            for (Future<EvaluationResponse> f : futures) {
                EvaluationResponse r = f.get(30, TimeUnit.SECONDS);
                if (r.evaluationStatuses() != null
                    && r.evaluationStatuses().contains(EvaluationStatus.REMOTE_EVALUATION_FAILURE.name())) {
                    failOpen++;
                } else {
                    allow++;
                    assertThat(r.action()).isEqualTo(EnforcementAction.ALLOW);
                }
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            pool.shutdownNow();
            System.out.printf(Locale.ROOT,
                "concurrency=%d hits=%d allow=%d failOpen=%d elapsedMs=%d%n",
                concurrency, hits.get(), allow, failOpen, elapsedMs);
            assertThat(allow + failOpen).isEqualTo(concurrency);
            // Healthy server: expect near-full success; allow small local flake budget
            assertThat(allow).isGreaterThan(concurrency - 5);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void concurrentUnavailableServerCallsFailOpenAndRemainBounded() throws Exception {
        int concurrency = 100;
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());
        RemoteEvaluationClient client = new RemoteEvaluationClient(
            "http://127.0.0.1:1",
            RemoteEvaluationController.PATH,
            API_KEY,
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            MAPPER,
            recording(outcomes));
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EvaluationResponse>> futures = new ArrayList<>();
        long t0 = System.nanoTime();
        for (int i = 0; i < concurrency; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return client.evaluate(req("down-" + idx));
            }));
        }
        start.countDown();
        for (Future<EvaluationResponse> f : futures) {
            EvaluationResponse r = f.get(30, TimeUnit.SECONDS);
            assertThat(r.proceed()).isTrue();
            assertThat(r.action()).isEqualTo(EnforcementAction.ALLOW);
            assertThat(r.anomalyScore()).isNull();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        pool.shutdownNow();
        System.out.printf(Locale.ROOT,
            "unavailable concurrency=%d elapsedMs=%d outcomeSample=%s%n",
            concurrency, elapsedMs, outcomes.stream().distinct().toList());
        assertThat(outcomes).isNotEmpty();
        assertThat(outcomes).allMatch(o ->
            o.equals(RemoteEvaluationOutcome.CONNECTION_FAILURE.name())
                || o.equals(RemoteEvaluationOutcome.TIMEOUT.name())
                || o.equals(RemoteEvaluationOutcome.UNEXPECTED.name()));
    }

    private static EvaluationRequest req(String correlationId) {
        return EvaluationRequest.builder()
            .correlationId(correlationId)
            .identityKey("id")
            .path("/api/char")
            .build();
    }

    private static RemoteEvaluationClient client(int port, Duration connect, Duration read) {
        return new RemoteEvaluationClient(
            "http://127.0.0.1:" + port,
            RemoteEvaluationController.PATH,
            API_KEY,
            connect,
            read,
            MAPPER,
            SentinelMetrics.NOOP);
    }

    private static HttpServer startAllowServer(AtomicInteger hits, long delayMs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RemoteEvaluationController.PATH, exchange -> {
            hits.incrementAndGet();
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            String corr = "ok";
            try {
                EvaluationRequest parsed = MAPPER.readValue(reqBytes, EvaluationRequest.class);
                if (parsed.correlationId() != null) {
                    corr = parsed.correlationId();
                }
            } catch (Exception ignored) {
                // keep default
            }
            byte[] resp = ("""
                {"contractVersion":1,"correlationId":"%s","action":"ALLOW","evaluationStatuses":["COMPLETE"],"proceed":true,"endpoint":"/api/char"}
                """.formatted(corr)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        return server;
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
