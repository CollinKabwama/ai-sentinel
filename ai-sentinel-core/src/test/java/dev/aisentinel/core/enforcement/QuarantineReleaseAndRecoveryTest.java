package dev.aisentinel.core.enforcement;

import dev.aisentinel.core.baseline.BaselineLifecycle;
import dev.aisentinel.core.baseline.BaselineRelearnMode;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.scoring.StatisticalScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import dev.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Quarantine release primitive + MONITOR non-mutation + baseline independence.
 */
class QuarantineReleaseAndRecoveryTest {

    private TelemetryEmitter telemetry;
    private HttpRequestView request;
    private EnforcementResponse response;

    @BeforeEach
    void setUp() {
        telemetry = mock(TelemetryEmitter.class);
        request = mock(HttpRequestView.class);
        response = mock(EnforcementResponse.class);
    }

    private CompositeEnforcementHandler handler(EnforcementScope scope, ClusterQuarantineWriter writer) {
        return new CompositeEnforcementHandler(403, 60_000L, 10.0, telemetry, 100, 60_000L, scope, writer, "tenant-a");
    }

    @Test
    void releaseExistingIdentityEndpointQuarantine() throws Exception {
        ClusterQuarantineWriter writer = mock(ClusterQuarantineWriter.class);
        var handler = handler(EnforcementScope.IDENTITY_ENDPOINT, writer);
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        assertThat(handler.isQuarantined("h1", "/api")).isTrue();

        assertThat(handler.releaseQuarantine("h1", "/api")).isTrue();
        assertThat(handler.isQuarantined("h1", "/api")).isFalse();
        assertThat(handler.getQuarantineCount()).isZero();
        verify(writer).clearQuarantine(eq("tenant-a"), eq("h1|/api"));
    }

    @Test
    void releaseMissingEntryIsIdempotent() {
        ClusterQuarantineWriter writer = mock(ClusterQuarantineWriter.class);
        var handler = handler(EnforcementScope.IDENTITY_ENDPOINT, writer);

        assertThat(handler.releaseQuarantine("missing", "/api")).isFalse();
        assertThat(handler.releaseQuarantine("missing", "/api")).isFalse();
        verify(writer, times(2)).clearQuarantine(eq("tenant-a"), eq("missing|/api"));
    }

    @Test
    void releaseTwiceSecondCallIsIdempotent() throws Exception {
        var handler = handler(EnforcementScope.IDENTITY_ENDPOINT, mock(ClusterQuarantineWriter.class));
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        assertThat(handler.releaseQuarantine("h1", "/api")).isTrue();
        assertThat(handler.releaseQuarantine("h1", "/api")).isFalse();
        assertThat(handler.isQuarantined("h1", "/api")).isFalse();
    }

    @Test
    void identityGlobalReleaseClearsAcrossEndpoints() throws Exception {
        var handler = handler(EnforcementScope.IDENTITY_GLOBAL, mock(ClusterQuarantineWriter.class));
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/a");
        assertThat(handler.isQuarantined("h1", "/a")).isTrue();
        assertThat(handler.isQuarantined("h1", "/b")).isTrue();

        assertThat(handler.releaseQuarantine("h1", "/unused")).isTrue();
        assertThat(handler.isQuarantined("h1", "/a")).isFalse();
        assertThat(handler.isQuarantined("h1", "/b")).isFalse();
    }

    @Test
    void identityEndpointReleaseDoesNotAffectOtherEndpointOrIdentity() throws Exception {
        var handler = handler(EnforcementScope.IDENTITY_ENDPOINT, mock(ClusterQuarantineWriter.class));
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/a");
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/b");
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h2", "/a");

        assertThat(handler.releaseQuarantine("h1", "/a")).isTrue();
        assertThat(handler.isQuarantined("h1", "/a")).isFalse();
        assertThat(handler.isQuarantined("h1", "/b")).isTrue();
        assertThat(handler.isQuarantined("h2", "/a")).isTrue();
    }

    @Test
    void releaseMidTtlStillClears() throws Exception {
        var handler = new CompositeEnforcementHandler(403, 60_000L, 10.0, telemetry, 100, 60_000L);
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        Thread.sleep(20);
        assertThat(handler.releaseQuarantine("h1", "/api")).isTrue();
        assertThat(handler.isQuarantined("h1", "/api")).isFalse();
    }

    @Test
    void releaseNearExpiryClearsOrIsHarmless() throws Exception {
        var handler = new CompositeEnforcementHandler(403, 40L, 10.0, telemetry, 100, 60_000L);
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        Thread.sleep(45);
        // Either still present then cleared, or already expired — both safe.
        handler.releaseQuarantine("h1", "/api");
        assertThat(handler.isQuarantined("h1", "/api")).isFalse();
    }

    @Test
    void reQuarantineAfterReleaseStillPossible() throws Exception {
        var handler = new CompositeEnforcementHandler(403, 60_000L, 10.0, telemetry, 100, 60_000L);
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        handler.releaseQuarantine("h1", "/api");
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        assertThat(handler.isQuarantined("h1", "/api")).isTrue();
    }

    @Test
    void concurrentReleaseAndIsQuarantinedDoesNotThrow() throws Exception {
        var handler = new CompositeEnforcementHandler(403, 60_000L, 10.0, telemetry, 100, 60_000L);
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < 40; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    handler.isQuarantined("h1", "/api");
                    handler.releaseQuarantine("h1", "/api");
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isZero();
        assertThat(handler.isQuarantined("h1", "/api")).isFalse();
    }

    @Test
    void concurrentReleaseAndApplyQuarantineRaceLeavesConsistentState() throws Exception {
        var handler = new CompositeEnforcementHandler(403, 60_000L, 10.0, telemetry, 100, 60_000L);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
                    handler.releaseQuarantine("h1", "/api");
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isZero();
        // Acceptable race: final state is either quarantined (last apply won) or clear (last release won).
        // Release does not create immunity from a concurrent/new quarantine decision.
        boolean quarantined = handler.isQuarantined("h1", "/api");
        if (quarantined) {
            assertThat(handler.getQuarantineCount()).isEqualTo(1);
        } else {
            assertThat(handler.getQuarantineCount()).isZero();
        }
    }

    @Test
    void clusterClearFailureDoesNotCrashAndLocalReleaseRetained() throws Exception {
        ClusterQuarantineWriter writer = mock(ClusterQuarantineWriter.class);
        doThrow(new RuntimeException("redis down")).when(writer).clearQuarantine(anyString(), anyString());
        var handler = handler(EnforcementScope.IDENTITY_ENDPOINT, writer);
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");

        assertThat(handler.releaseQuarantine("h1", "/api")).isTrue();
        assertThat(handler.isQuarantined("h1", "/api")).isFalse();
    }

    @Test
    void releaseDoesNotResetBaseline() throws Exception {
        StatisticalScorer statistical = new StatisticalScorer(100, 60_000L, 2, 0.4);
        var features = dev.aisentinel.core.model.RequestFeatures.builder()
            .identityHash("h1").endpoint("/api").timestampMillis(1L)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        for (int i = 0; i < 5; i++) {
            statistical.update(features);
        }
        assertThat(statistical.isWarmup(features)).isFalse();

        BaselineLifecycle lifecycle = new BaselineLifecycle(statistical, BaselineRelearnMode.EXPLICIT_ONLY, SentinelMetrics.NOOP);
        var handler = new CompositeEnforcementHandler(403, 60_000L, 10.0, telemetry);
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        handler.releaseQuarantine("h1", "/api");

        assertThat(handler.isQuarantined("h1", "/api")).isFalse();
        assertThat(statistical.isWarmup(features)).isFalse(); // baseline untouched
        assertThat(lifecycle.reset("h1", "/api")).isTrue(); // explicit reset still works independently
        assertThat(statistical.isWarmup(features)).isTrue();
    }

    @Test
    void baselineResetDoesNotClearQuarantine() throws Exception {
        StatisticalScorer statistical = new StatisticalScorer(100, 60_000L, 2, 0.4);
        var features = dev.aisentinel.core.model.RequestFeatures.builder()
            .identityHash("h1").endpoint("/api").timestampMillis(1L)
            .requestsPerWindow(1).endpointEntropy(0).tokenAgeSeconds(60)
            .parameterCount(0).payloadSizeBytes(0).headerFingerprintHash(0).ipBucket(0).build();
        for (int i = 0; i < 5; i++) {
            statistical.update(features);
        }
        BaselineLifecycle lifecycle = new BaselineLifecycle(statistical, BaselineRelearnMode.EXPLICIT_ONLY, SentinelMetrics.NOOP);
        var handler = new CompositeEnforcementHandler(403, 60_000L, 10.0, telemetry);
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");

        assertThat(lifecycle.reset("h1", "/api")).isTrue();
        assertThat(handler.isQuarantined("h1", "/api")).isTrue(); // quarantine independent
    }

    @Test
    void monitorOnlyDoesNotCreateQuarantineStateOnWouldQuarantine() throws Exception {
        CompositeEnforcementHandler delegate = new CompositeEnforcementHandler(403, 60_000L, 10.0, telemetry);
        List<TelemetryEvent> events = new ArrayList<>();
        TelemetryEmitter recording = events::add;
        MonitorOnlyEnforcementHandler monitor = new MonitorOnlyEnforcementHandler(delegate, recording);

        assertThat(monitor.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api")).isTrue();
        assertThat(delegate.getQuarantineCount()).isZero();
        assertThat(delegate.isQuarantined("h1", "/api")).isFalse();
        assertThat(events).extracting(TelemetryEvent::type).contains("PolicyActionApplied");
        assertThat(events.stream().anyMatch(e ->
            "MONITOR_WOULD_QUARANTINE".equals(String.valueOf(e.payload().get("action"))))).isTrue();
    }

    @Test
    void monitorOnlyStillForwardsReleaseToDelegate() throws Exception {
        CompositeEnforcementHandler delegate = new CompositeEnforcementHandler(403, 60_000L, 10.0, telemetry);
        delegate.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        MonitorOnlyEnforcementHandler monitor = new MonitorOnlyEnforcementHandler(delegate, telemetry);

        assertThat(monitor.releaseQuarantine("h1", "/api")).isTrue();
        assertThat(delegate.isQuarantined("h1", "/api")).isFalse();
    }

    @Test
    void switchingFromMonitorToEnforceDoesNotInheritMonitorWouldQuarantine() throws Exception {
        CompositeEnforcementHandler shared = new CompositeEnforcementHandler(403, 60_000L, 10.0, telemetry);
        MonitorOnlyEnforcementHandler monitor = new MonitorOnlyEnforcementHandler(shared, telemetry);
        monitor.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        // Simulate mode switch: use shared composite directly (ENFORCE).
        assertThat(shared.isQuarantined("h1", "/api")).isFalse();
        assertThat(shared.getQuarantineCount()).isZero();
    }

    @Test
    void monitorDoesNotCallClusterPublish() throws Exception {
        ClusterQuarantineWriter writer = mock(ClusterQuarantineWriter.class);
        CompositeEnforcementHandler delegate = handler(EnforcementScope.IDENTITY_ENDPOINT, writer);
        MonitorOnlyEnforcementHandler monitor = new MonitorOnlyEnforcementHandler(delegate, telemetry);

        monitor.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        verify(writer, never()).publishQuarantine(anyString(), anyString(), anyLong());
    }

    @Test
    void releaseEmitsQuarantineReleasedTelemetry() throws Exception {
        List<TelemetryEvent> events = new ArrayList<>();
        TelemetryEmitter recording = events::add;
        var handler = new CompositeEnforcementHandler(403, 60_000L, 10.0, recording);
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        events.clear();
        handler.releaseQuarantine("h1", "/api");
        assertThat(events).extracting(TelemetryEvent::type).contains("QuarantineReleased");
    }
}
