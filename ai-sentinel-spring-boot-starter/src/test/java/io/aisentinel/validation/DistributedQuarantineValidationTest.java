package io.aisentinel.validation;

import io.aisentinel.autoconfigure.actuator.SentinelActuatorEndpoint;
import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineKeyBuilder;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader;
import io.aisentinel.autoconfigure.metrics.MicrometerSentinelMetrics;
import io.aisentinel.autoconfigure.web.SentinelFilter;
import io.aisentinel.core.SentinelPipeline;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.core.feature.FeatureExtractor;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.policy.PolicyEngine;
import io.aisentinel.core.runtime.StartupGrace;
import io.aisentinel.core.scoring.CompositeScorer;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import io.aisentinel.distributed.enforcement.ClusterAwareEnforcementHandler;
import io.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import io.aisentinel.distributed.quarantine.NoopClusterQuarantineWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5.3 — validation in a <strong>single JVM</strong>: Node A uses the Spring-wired
 * {@link CompositeEnforcementHandler} (writer → primary {@link StringRedisTemplate}). Node B is modeled as a
 * <strong>second Lettuce client</strong> to the same Redis plus a separately constructed
 * {@link ClusterAwareEnforcementHandler} and {@link SentinelFilter} (see enforcement test). Requires Docker for
 * Testcontainers; tests are skipped when Docker is unavailable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = DistributedValidationTestApplication.class,
    webEnvironment = WebEnvironment.MOCK,
    properties = {
        "ai.sentinel.enabled=true",
        "ai.sentinel.mode=ENFORCE",
        "ai.sentinel.distributed.enabled=true",
        "ai.sentinel.distributed.cluster-quarantine-read-enabled=true",
        "ai.sentinel.distributed.cluster-quarantine-write-enabled=true",
        "ai.sentinel.distributed.redis.enabled=true",
        "ai.sentinel.distributed.tenant-id=validation-tenant",
        "ai.sentinel.distributed.cache.enabled=true",
        "ai.sentinel.distributed.cache.ttl=400ms",
        "ai.sentinel.distributed.cache.max-entries=100",
        "ai.sentinel.distributed.redis.max-in-flight-quarantine-writes=64",
        "ai.sentinel.distributed.redis.lookup-timeout=2s",
        "spring.data.redis.timeout=3s"
    })
class DistributedQuarantineValidationTest {

    private static final String ENFORCEMENT_CLIENT_IP = "203.0.113.50";
    private static final String ENFORCEMENT_ENDPOINT = "/api/phase53-ping";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private SentinelProperties sentinelProperties;

    @Autowired
    private MicrometerSentinelMetrics micrometerSentinelMetrics;

    @Autowired
    private DistributedQuarantineStatus distributedQuarantineStatus;

    @Autowired
    @Qualifier("enforcementHandlerImpl")
    private CompositeEnforcementHandler compositeEnforcementHandler;

    @Autowired
    private ClusterQuarantineWriter clusterQuarantineWriter;

    @Autowired(required = false)
    private SentinelActuatorEndpoint sentinelActuatorEndpoint;

    @Autowired
    private Phase53PingController phase53PingController;

    @Autowired
    private FeatureExtractor featureExtractor;

    @Autowired
    private CompositeScorer compositeScorer;

    @Autowired
    private PolicyEngine policyEngine;

    @Autowired
    private TelemetryEmitter telemetryEmitter;

    @Autowired
    private StartupGrace startupGrace;

    @Test
    @Order(4)
    void nodeAQuarantineWritesRedis_nodeBReaderSeesClusterQuarantine_separateRedisClient_metricDeltas() throws Exception {
        MetricSnapshot before = MetricSnapshot.capture(micrometerSentinelMetrics, distributedQuarantineStatus);

        String identity = "node-test-id";
        String endpoint = "/api/validate";
        String enforcementKey = identity + "|" + endpoint;
        String tenant = sentinelProperties.getDistributed().getTenantId();
        String redisKey = DistributedQuarantineKeyBuilder.redisKey(
            sentinelProperties.getDistributed().getRedis().getKeyPrefix(), tenant, enforcementKey);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(java.io.OutputStream.nullOutputStream(), true));

        boolean allowed = compositeEnforcementHandler.apply(EnforcementAction.QUARANTINE, request, response, identity, endpoint);
        assertThat(allowed).isFalse();
        assertThat(compositeEnforcementHandler.isQuarantined(identity, endpoint)).isTrue();

        awaitKeyPresent(stringRedisTemplate, redisKey, 5_000);

        try (NodeBRedis nodeB = NodeBRedis.connect(REDIS)) {
            var statusB = new DistributedQuarantineStatus();
            var readerB = new RedisClusterQuarantineReader(nodeB.template, sentinelProperties, micrometerSentinelMetrics, statusB);
            try {
                var until = readerB.quarantineUntil(tenant, enforcementKey);
                assertThat(until).isPresent();
                assertThat(until.getAsLong()).isGreaterThan(System.currentTimeMillis());
            } finally {
                readerB.destroy();
            }
        }

        MetricSnapshot after = MetricSnapshot.capture(micrometerSentinelMetrics, distributedQuarantineStatus);
        assertThat(after.writeAttempts - before.writeAttempts).isGreaterThan(0);
        assertThat(after.writeSuccesses - before.writeSuccesses).isGreaterThan(0);
        assertThat(after.lookups - before.lookups).isGreaterThan(0);
        assertThat(after.redisWriterDegraded).isFalse();
    }

    @Test
    @Order(5)
    void nodeAWritesQuarantine_nodeBClusterAwareFilterBlocksHttp() throws Exception {
        MetricSnapshot before = MetricSnapshot.capture(micrometerSentinelMetrics, distributedQuarantineStatus);

        String identityHash = sha256HexIdentity(ENFORCEMENT_CLIENT_IP);
        String tenant = sentinelProperties.getDistributed().getTenantId();
        String enforcementKey = identityHash + "|" + ENFORCEMENT_ENDPOINT;
        String redisKey = DistributedQuarantineKeyBuilder.redisKey(
            sentinelProperties.getDistributed().getRedis().getKeyPrefix(), tenant, enforcementKey);

        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn(ENFORCEMENT_ENDPOINT);
        when(response.getWriter()).thenReturn(new PrintWriter(java.io.OutputStream.nullOutputStream(), true));

        boolean allowed = compositeEnforcementHandler.apply(EnforcementAction.QUARANTINE, request, response, identityHash, ENFORCEMENT_ENDPOINT);
        assertThat(allowed).isFalse();
        assertThat(compositeEnforcementHandler.isQuarantined(identityHash, ENFORCEMENT_ENDPOINT)).isTrue();

        awaitKeyPresent(stringRedisTemplate, redisKey, 5_000);
        // Async Redis SET runs on a virtual thread; Micrometer success is recorded there. On slow CI, polling
        // avoids racing the main thread snapshot before counters reflect the completed write.
        awaitClusterWriteMetricsIncremented(before, 5_000);

        try (NodeBRedis nodeB = NodeBRedis.connect(REDIS)) {
            var readerStatusB = new DistributedQuarantineStatus();
            var readerB = new RedisClusterQuarantineReader(nodeB.template, sentinelProperties, micrometerSentinelMetrics, readerStatusB);
            CompositeEnforcementHandler localOnlyB = new CompositeEnforcementHandler(
                sentinelProperties.getBlockStatusCode(),
                sentinelProperties.getQuarantineDurationMs(),
                sentinelProperties.getThrottleRequestsPerSecond(),
                telemetryEmitter,
                sentinelProperties.getInternalMapMaxKeys(),
                sentinelProperties.getInternalMapTtl() != null ? sentinelProperties.getInternalMapTtl().toMillis() : 300_000L,
                sentinelProperties.getEnforcementScope(),
                NoopClusterQuarantineWriter.INSTANCE,
                tenant);
            ClusterAwareEnforcementHandler enforcementB = new ClusterAwareEnforcementHandler(
                localOnlyB,
                readerB,
                tenant,
                sentinelProperties.getEnforcementScope());
            SentinelPipeline pipelineB = new SentinelPipeline(
                featureExtractor,
                compositeScorer,
                policyEngine,
                enforcementB,
                telemetryEmitter,
                startupGrace,
                micrometerSentinelMetrics);
            SentinelFilter filterB = new SentinelFilter(pipelineB, sentinelProperties, micrometerSentinelMetrics);
            MockMvc mockMvcB = MockMvcBuilders.standaloneSetup(phase53PingController).addFilters(filterB).build();
            try {
                int expectedStatus = sentinelProperties.getBlockStatusCode();
                mockMvcB.perform(get(ENFORCEMENT_ENDPOINT).with(remoteAddr(ENFORCEMENT_CLIENT_IP)))
                    .andExpect(status().is(expectedStatus))
                    .andExpect(content().string("Quarantined"));

                // Cluster read sets action to QUARANTINE; SentinelPipeline then calls apply(QUARANTINE) on the
                // delegate, which records local quarantine (mirrors Redis-backed state for this node).
                assertThat(localOnlyB.isQuarantined(identityHash, ENFORCEMENT_ENDPOINT)).isTrue();

                MetricSnapshot after = MetricSnapshot.capture(micrometerSentinelMetrics, distributedQuarantineStatus);
                assertThat(after.lookups - before.lookups).isGreaterThan(0);
                assertThat(after.redisWriterDegraded).isFalse();
            } finally {
                readerB.destroy();
            }
        }
    }

    @Test
    @Order(2)
    void actuatorExposesDistributedFlagsAndMetricSummary() {
        assertThat(sentinelActuatorEndpoint).isNotNull();
        Map<String, Object> info = sentinelActuatorEndpoint.info();
        assertThat(info.get("distributedEnabled")).isEqualTo(true);
        assertThat(info.get("distributedClusterQuarantineReadEnabled")).isEqualTo(true);
        assertThat(info.get("distributedClusterQuarantineWriteEnabled")).isEqualTo(true);
        assertThat(info.get("clusterQuarantineWriterType")).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> dq = (Map<String, Object>) info.get("distributedQuarantine");
        assertThat(dq).isNotNull();
        assertThat(dq).containsKeys("redisReaderDegraded", "redisWriterDegraded", "lastRedisErrorTimeMillis",
            "lastWriteErrorTimeMillis");

        @SuppressWarnings("unchecked")
        Map<String, Object> dm = (Map<String, Object>) info.get("distributedMetrics");
        assertThat(dm).isNotNull();
        assertThat(dm).containsKeys(
            "quarantineWriteAttemptCount",
            "quarantineWriteSuccessCount",
            "quarantineWriteFailureCount",
            "quarantineWriteSkippedExpiredCount",
            "quarantineWriteDroppedCount",
            "quarantineWriteSchedulerRejectedCount",
            "quarantineLookupCount");
    }

    @Test
    @Order(3)
    void cacheServesStalePositiveUntilRedisKeyDeletedThenExpiresAndFailsOpen() throws Exception {
        String tenant = sentinelProperties.getDistributed().getTenantId();
        String enforcementKey = "cache-stale|/z";
        String redisKey = DistributedQuarantineKeyBuilder.redisKey(
            sentinelProperties.getDistributed().getRedis().getKeyPrefix(), tenant, enforcementKey);

        long until = System.currentTimeMillis() + 600_000;
        stringRedisTemplate.opsForValue().set(redisKey, Long.toString(until));

        var status = new DistributedQuarantineStatus();
        var reader = new RedisClusterQuarantineReader(stringRedisTemplate, sentinelProperties, micrometerSentinelMetrics, status);
        try {
            var first = reader.quarantineUntil(tenant, enforcementKey);
            assertThat(first).isPresent();
            assertThat(first.getAsLong()).isEqualTo(until);

            stringRedisTemplate.delete(redisKey);
            assertThat(stringRedisTemplate.hasKey(redisKey)).isFalse();

            var secondWhileCacheWarm = reader.quarantineUntil(tenant, enforcementKey);
            assertThat(secondWhileCacheWarm).isPresent();
            assertThat(secondWhileCacheWarm.getAsLong()).isEqualTo(until);

            Thread.sleep(500);

            var afterCacheExpiry = reader.quarantineUntil(tenant, enforcementKey);
            assertThat(afterCacheExpiry).isEmpty();
        } finally {
            reader.destroy();
        }
    }

    @Test
    @Order(1)
    void writerBeanIsRedisImplementationInSharedRedisContext() {
        assertThat(clusterQuarantineWriter).isInstanceOf(
            io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineWriter.class);
    }

    private static RequestPostProcessor remoteAddr(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String sha256HexIdentity(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((raw != null ? raw : "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf((raw != null ? raw : "").hashCode());
        }
    }

    private void awaitKeyPresent(StringRedisTemplate tpl, String redisKey, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(tpl.hasKey(redisKey))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Redis key not found within timeout: " + redisKey);
    }

    private void awaitClusterWriteMetricsIncremented(MetricSnapshot before, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            MetricSnapshot now = MetricSnapshot.capture(micrometerSentinelMetrics, distributedQuarantineStatus);
            if (now.writeAttempts > before.writeAttempts && now.writeSuccesses > before.writeSuccesses) {
                assertThat(now.redisWriterDegraded).isFalse();
                return;
            }
            Thread.sleep(20);
        }
        MetricSnapshot stuck = MetricSnapshot.capture(micrometerSentinelMetrics, distributedQuarantineStatus);
        throw new AssertionError("Cluster quarantine write metrics did not increment within timeout: attempts "
            + before.writeAttempts + " -> " + stuck.writeAttempts + ", successes " + before.writeSuccesses + " -> "
            + stuck.writeSuccesses);
    }

    private record MetricSnapshot(
        long writeAttempts,
        long writeSuccesses,
        long lookups,
        boolean redisWriterDegraded
    ) {
        static MetricSnapshot capture(MicrometerSentinelMetrics metrics, DistributedQuarantineStatus status) {
            return new MetricSnapshot(
                metrics.getDistributedQuarantineWriteAttemptCount(),
                metrics.getDistributedQuarantineWriteSuccessCount(),
                metrics.getDistributedQuarantineLookupCount(),
                status.isRedisWriterDegraded());
        }
    }

    /**
     * Second {@link LettuceConnectionFactory} + {@link StringRedisTemplate} to the same Testcontainers Redis as the
     * primary Spring bean (simulates another JVM’s Redis client, not another process).
     */
    private static final class NodeBRedis implements AutoCloseable {
        private final LettuceConnectionFactory factory;
        final StringRedisTemplate template;

        private NodeBRedis(LettuceConnectionFactory factory, StringRedisTemplate template) {
            this.factory = factory;
            this.template = template;
        }

        static NodeBRedis connect(GenericContainer<?> redis) {
            RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration();
            cfg.setHostName(redis.getHost());
            cfg.setPort(redis.getMappedPort(6379));
            LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(3))
                .build();
            LettuceConnectionFactory f = new LettuceConnectionFactory(cfg, client);
            f.afterPropertiesSet();
            StringRedisTemplate tpl = new StringRedisTemplate();
            tpl.setConnectionFactory(f);
            tpl.afterPropertiesSet();
            return new NodeBRedis(f, tpl);
        }

        @Override
        public void close() {
            factory.destroy();
        }
    }
}
