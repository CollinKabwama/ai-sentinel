package dev.aisentinel.benchmark.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.autoconfigure.identity.trust.RedisFailOpenBehavioralBaselineStore;
import dev.aisentinel.autoconfigure.evaluation.RemoteEvaluationConstants;
import dev.aisentinel.autoconfigure.web.RemoteEvaluationController;
import dev.aisentinel.core.contract.LocalEvaluationBridge;
import dev.aisentinel.core.contract.LocalEvaluationExecutor;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.model.AuthenticationContext;
import dev.aisentinel.core.identity.model.IdentityContext;
import dev.aisentinel.core.identity.model.IdentityRiskSignals;
import dev.aisentinel.core.identity.model.SessionContext;
import dev.aisentinel.core.identity.model.TrustScore;
import dev.aisentinel.core.identity.spi.IdentityContextResolver;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.identity.trust.BehavioralIdentityTrustEvaluator;
import dev.aisentinel.core.identity.trust.IdentityBehavioralBaselineStore;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.store.BaselineStore;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class RemoteBenchmarkServer implements AutoCloseable {

    private final int port;
    private final ObjectMapper objectMapper;
    private final RemoteFaultController faults;
    private final BenchmarkSentinelMetrics metrics;
    private final RemoteEvaluationController controller;
    private final LettuceConnectionFactory redisConnectionFactory;
    private final RedisFailOpenBehavioralBaselineStore redisBehaviorStore;

    private HttpServer server;
    private ExecutorService executor;

    private RemoteBenchmarkServer(int port,
                                  RemoteEvaluationController controller,
                                  BenchmarkSentinelMetrics metrics,
                                  LettuceConnectionFactory redisConnectionFactory,
                                  RedisFailOpenBehavioralBaselineStore redisBehaviorStore) {
        this.port = port;
        this.controller = controller;
        this.metrics = metrics;
        this.redisConnectionFactory = redisConnectionFactory;
        this.redisBehaviorStore = redisBehaviorStore;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.faults = new RemoteFaultController();
    }

    static RemoteBenchmarkServer remoteOnly() throws IOException {
        return create(reservePort(), TrustMode.DISABLED, null, null, Duration.ofMillis(100));
    }

    static RemoteBenchmarkServer localTrust() throws IOException {
        return create(reservePort(), TrustMode.LOCAL_MEMORY, null, null, Duration.ofMillis(100));
    }

    static RemoteBenchmarkServer redisBacked(String redisHost, int redisPort, Duration redisCommandTimeout)
        throws IOException {
        return create(reservePort(), TrustMode.REDIS, redisHost, redisPort, redisCommandTimeout);
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext(RemoteEvaluationController.PATH, this::handle);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.start();
        awaitListening();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    int port() {
        return port;
    }

    BenchmarkSentinelMetrics metrics() {
        return metrics;
    }

    RemoteFaultController faults() {
        return faults;
    }

    @Override
    public void close() {
        stop();
        try {
            if (redisBehaviorStore != null) {
                redisBehaviorStore.destroy();
            }
        } catch (Exception ignored) {
        }
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    private static int reservePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private void awaitListening() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for benchmark server", e);
                }
            }
        }
        throw new IllegalStateException("Remote benchmark server did not start listening on port " + port);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            RemoteFaultController.Mode mode = faults.mode();
            if (mode == RemoteFaultController.Mode.DELAY) {
                try {
                    Thread.sleep(faults.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while delaying benchmark response", e);
                }
            }
            if (mode == RemoteFaultController.Mode.MALFORMED_RESPONSE) {
                writeBytes(exchange, 200, "{\"contractVersion\":1,\"correlationId\":".getBytes(StandardCharsets.UTF_8));
                return;
            }

            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", RemoteEvaluationController.PATH);
            servletRequest.addHeader(RemoteEvaluationConstants.API_KEY_HEADER,
                exchange.getRequestHeaders().getFirst(RemoteEvaluationConstants.API_KEY_HEADER));
            servletRequest.setContentType("application/json");
            servletRequest.setContent(requestBytes);
            Object body = null;
            if (requestBytes.length > 0) {
                body = objectMapper.readValue(requestBytes, dev.aisentinel.core.contract.EvaluationRequest.class);
            }
            ResponseEntity<?> response =
                controller.evaluate((dev.aisentinel.core.contract.EvaluationRequest) body, servletRequest);
            Object responseBody = response.getBody();
            if (responseBody == null) {
                exchange.sendResponseHeaders(response.getStatusCode().value(), -1);
                return;
            }
            byte[] encoded = responseBody instanceof String text
                ? text.getBytes(StandardCharsets.UTF_8)
                : objectMapper.writeValueAsBytes(responseBody);
            writeBytes(exchange, response.getStatusCode().value(), encoded);
        } finally {
            exchange.close();
        }
    }

    private static void writeBytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static RemoteBenchmarkServer create(int port,
                                                TrustMode trustMode,
                                                String redisHost,
                                                Integer redisPort,
                                                Duration redisCommandTimeout) throws IOException {
        BenchmarkSentinelMetrics metrics = new BenchmarkSentinelMetrics();
        SentinelProperties props = new SentinelProperties();
        props.setMode(SentinelProperties.Mode.MONITOR);
        props.setWarmupMinSamples(0);
        props.getEvaluation().getServer().setEnabled(true);
        props.getEvaluation().getServer().setApiKey(DeploymentBenchmarkMain.API_KEY);
        props.getEvaluation().getServer().setMaxRequestBytes(262_144);

        BaselineStore featureBaselineStore = new BaselineStore(Duration.ofMinutes(5), 100_000);
        FeatureExtractor featureExtractor = new DefaultFeatureExtractor(featureBaselineStore, 100_000, 300_000L);
        StatisticalScorer scorer = new StatisticalScorer(100_000, 300_000L, 0, 0.0, metrics);
        var policyEngine = new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8);
        EnforcementHandler enforcementHandler = new EnforcementHandler() {
            @Override
            public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                 String identityHash, String endpoint) {
                return action == EnforcementAction.ALLOW || action == EnforcementAction.MONITOR;
            }

            @Override
            public boolean isQuarantined(String identityHash, String endpoint) {
                return false;
            }
        };

        IdentityContextResolver resolver = trustMode == TrustMode.DISABLED
            ? (request, identityHash, ctx) -> { }
            : (request, identityHash, ctx) -> ctx.put(
                dev.aisentinel.core.identity.IdentityContextKeys.IDENTITY_CONTEXT,
                new IdentityContext(
                    AuthenticationContext.ofPrincipal(identityHash == null ? "benchmark-user" : identityHash),
                    SessionContext.none(),
                    TrustScore.fullyTrusted(),
                    IdentityRiskSignals.empty()));

        Object trustEvaluatorOrResources = switch (trustMode) {
            case DISABLED -> NoopTrustEvaluator.INSTANCE;
            case LOCAL_MEMORY -> new BehavioralIdentityTrustEvaluator(
                new IdentityBehavioralBaselineStore(Duration.ofHours(1), 10_000),
                25.0,
                0.82,
                0.75);
            case REDIS -> {
                SentinelProperties.TrustDistributed distributed = props.getIdentity().getTrust().getDistributed();
                distributed.setEnabled(true);
                distributed.setCommandTimeout(redisCommandTimeout);
                LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration(redisHost, redisPort),
                    LettuceClientConfiguration.builder()
                        .commandTimeout(redisCommandTimeout.multipliedBy(2))
                        .shutdownTimeout(Duration.ZERO)
                        .build());
                factory.afterPropertiesSet();
                StringRedisTemplate template = new StringRedisTemplate(factory);
                template.afterPropertiesSet();
                IdentityBehavioralBaselineStore fallback =
                    new IdentityBehavioralBaselineStore(Duration.ofHours(1), 10_000);
                RedisFailOpenBehavioralBaselineStore store =
                    new RedisFailOpenBehavioralBaselineStore(template, fallback, props, metrics);
                yield new TrustEvaluatorResources(
                    new BehavioralIdentityTrustEvaluator(store, 25.0, 0.82, 0.75),
                    factory,
                    store);
            }
        };

        LettuceConnectionFactory factory = trustEvaluatorOrResources instanceof TrustEvaluatorResources resources
            ? resources.redisConnectionFactory()
            : null;
        RedisFailOpenBehavioralBaselineStore store = trustEvaluatorOrResources instanceof TrustEvaluatorResources resources
            ? resources.redisStore()
            : null;
        dev.aisentinel.core.identity.spi.TrustEvaluator evaluator =
            trustEvaluatorOrResources instanceof TrustEvaluatorResources resources
            ? resources.trustEvaluator()
            : (dev.aisentinel.core.identity.spi.TrustEvaluator) trustEvaluatorOrResources;

        SentinelDecisionEngine engine = new SentinelDecisionEngine(
            scorer,
            policyEngine,
            enforcementHandler,
            event -> { },
            StartupGrace.NEVER,
            metrics,
            evaluator,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE,
            EnforcementAction.MONITOR);
        LocalEvaluationBridge bridge = new LocalEvaluationBridge(featureExtractor, engine, resolver);
        RemoteEvaluationController controller = new RemoteEvaluationController(new LocalEvaluationExecutor(bridge), props);
        return new RemoteBenchmarkServer(port, controller, metrics, factory, store);
    }

    private enum TrustMode {
        DISABLED,
        LOCAL_MEMORY,
        REDIS
    }

    private record TrustEvaluatorResources(
        dev.aisentinel.core.identity.spi.TrustEvaluator trustEvaluator,
        LettuceConnectionFactory redisConnectionFactory,
        RedisFailOpenBehavioralBaselineStore redisStore
    ) {
    }
}
