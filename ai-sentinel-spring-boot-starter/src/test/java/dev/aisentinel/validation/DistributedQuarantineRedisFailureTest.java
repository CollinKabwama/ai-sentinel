package dev.aisentinel.validation;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.autoconfigure.distributed.DistributedQuarantineStatus;
import dev.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineReader;
import dev.aisentinel.autoconfigure.distributed.quarantine.RedisClusterQuarantineWriter;
import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.distributed.quarantine.NoopClusterQuarantineWriter;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis unreachable: read fail-open with degraded flag; local quarantine still applied; async write failures
 * do not propagate to the request thread. No Docker required.
 */
class DistributedQuarantineRedisFailureTest {

    private LettuceConnectionFactory connectionFactory;

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
            connectionFactory = null;
        }
    }

    @Test
    void clusterReaderFailOpenWhenRedisUnreachable() throws Exception {
        StringRedisTemplate badTemplate = badRedisTemplate(freeListeningPort());
        SentinelProperties props = distributedProps();
        var status = new DistributedQuarantineStatus();
        var metrics = new CountingSentinelMetrics();
        var reader = new RedisClusterQuarantineReader(badTemplate, props, metrics, status);
        try {
            var out = reader.quarantineUntil("t", "k|/x");
            assertThat(out).isEmpty();
            assertThat(status.isRedisReaderDegraded()).isTrue();
            assertThat(metrics.redisTimeouts.get() + metrics.redisFailures.get()).isGreaterThanOrEqualTo(1);
        } finally {
            reader.destroy();
        }
    }

    @Test
    void localQuarantineStillAppliedWhenClusterWriterUsesUnreachableRedis() throws Exception {
        StringRedisTemplate badTemplate = badRedisTemplate(freeListeningPort());
        SentinelProperties props = distributedProps();
        var status = new DistributedQuarantineStatus();
        var metrics = new CountingSentinelMetrics();
        var writer = new RedisClusterQuarantineWriter(badTemplate, props, metrics, status);
        var telemetry = mock(TelemetryEmitter.class);
        var composite = new CompositeEnforcementHandler(
            429, 60_000L, 5.0, telemetry, 100, 60_000L,
            EnforcementScope.IDENTITY_ENDPOINT,
            writer,
            "t");
        try {
            HttpRequestView request = mock(HttpRequestView.class);
            EnforcementResponse response = mock(EnforcementResponse.class);

            boolean allowed = composite.apply(EnforcementAction.QUARANTINE, request, response, "id1", "/api");
            assertThat(allowed).isFalse();
            assertThat(composite.isQuarantined("id1", "/api")).isTrue();

            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (status.isRedisWriterDegraded() && metrics.writeFailures.get() >= 1) {
                    break;
                }
                Thread.sleep(50);
            }
            assertThat(status.isRedisWriterDegraded()).isTrue();
            assertThat(metrics.writeFailures.get()).isGreaterThanOrEqualTo(1);
        } finally {
            writer.destroy();
        }
    }

    @Test
    void noopWriterDoesNotTouchRedisOrStatus() throws Exception {
        SentinelProperties props = distributedProps();
        var telemetry = mock(TelemetryEmitter.class);
        var composite = new CompositeEnforcementHandler(
            429, 60_000L, 5.0, telemetry, 100, 60_000L,
            EnforcementScope.IDENTITY_ENDPOINT,
            NoopClusterQuarantineWriter.INSTANCE,
            "t");
        HttpRequestView request = mock(HttpRequestView.class);
        EnforcementResponse response = mock(EnforcementResponse.class);
        assertThat(composite.apply(EnforcementAction.QUARANTINE, request, response, "x", "/y")).isFalse();
        assertThat(composite.isQuarantined("x", "/y")).isTrue();
    }

    private static int freeListeningPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private StringRedisTemplate badRedisTemplate(int port) {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration();
        cfg.setHostName("127.0.0.1");
        cfg.setPort(port);
        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(500))
            .build();
        connectionFactory = new LettuceConnectionFactory(cfg, client);
        connectionFactory.afterPropertiesSet();
        var tpl = new StringRedisTemplate();
        tpl.setConnectionFactory(connectionFactory);
        tpl.afterPropertiesSet();
        return tpl;
    }

    private static SentinelProperties distributedProps() {
        SentinelProperties props = new SentinelProperties();
        props.getDistributed().setEnabled(true);
        props.getDistributed().setClusterQuarantineReadEnabled(true);
        props.getDistributed().getRedis().setEnabled(true);
        props.getDistributed().getRedis().setLookupTimeout(Duration.ofMillis(200));
        props.getDistributed().getCache().setEnabled(false);
        return props;
    }

    private static final class CountingSentinelMetrics implements SentinelMetrics {
        final AtomicInteger redisFailures = new AtomicInteger();
        final AtomicInteger redisTimeouts = new AtomicInteger();
        final AtomicInteger writeFailures = new AtomicInteger();

        @Override
        public void recordDistributedRedisTimeout() {
            redisTimeouts.incrementAndGet();
        }

        @Override
        public void recordDistributedRedisFailure() {
            redisFailures.incrementAndGet();
        }

        @Override
        public void recordDistributedQuarantineWriteFailure() {
            writeFailures.incrementAndGet();
        }
    }
}
