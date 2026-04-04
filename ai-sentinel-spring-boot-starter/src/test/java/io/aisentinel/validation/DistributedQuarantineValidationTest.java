package io.aisentinel.validation;

import io.aisentinel.autoconfigure.actuator.SentinelActuatorEndpoint;
import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineKeyBuilder;
import io.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader;
import io.aisentinel.autoconfigure.metrics.MicrometerSentinelMetrics;
import io.aisentinel.core.enforcement.CompositeEnforcementHandler;
import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.PrintWriter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/**
 * Phase 5.3 — multi-node style validation against a real Redis (Testcontainers).
 * Skipped automatically when Docker is not available ({@code disabledWithoutDocker}).
 */
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
        "spring.data.redis.timeout=3s"
    })
class DistributedQuarantineValidationTest {

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
    @Qualifier("enforcementHandlerImpl")
    private CompositeEnforcementHandler compositeEnforcementHandler;

    @Autowired
    private ClusterQuarantineWriter clusterQuarantineWriter;

    @Autowired(required = false)
    private SentinelActuatorEndpoint sentinelActuatorEndpoint;

    @Test
    void nodeAQuarantineWritesRedis_nodeBReaderSeesClusterQuarantine() throws Exception {
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

        awaitKeyPresent(redisKey, 5_000);

        var statusB = new DistributedQuarantineStatus();
        var readerB = new RedisClusterQuarantineReader(stringRedisTemplate, sentinelProperties, micrometerSentinelMetrics, statusB);
        try {
            var until = readerB.quarantineUntil(tenant, enforcementKey);
            assertThat(until).isPresent();
            assertThat(until.getAsLong()).isGreaterThan(System.currentTimeMillis());
        } finally {
            readerB.destroy();
        }
    }

    @Test
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
    void writerBeanIsRedisImplementationInSharedRedisContext() {
        assertThat(clusterQuarantineWriter).isInstanceOf(
            io.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineWriter.class);
    }

    private void awaitKeyPresent(String redisKey, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(redisKey))) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Redis key not found within timeout: " + redisKey);
    }
}
