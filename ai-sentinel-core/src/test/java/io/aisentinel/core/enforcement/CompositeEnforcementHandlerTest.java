package io.aisentinel.core.enforcement;

import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import io.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import io.aisentinel.distributed.quarantine.NoopClusterQuarantineWriter;
import io.aisentinel.distributed.throttle.ClusterThrottleStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CompositeEnforcementHandlerTest {

    @Test
    void getQuarantineCountReturnsActiveQuarantines() throws Exception {
        handler = new CompositeEnforcementHandler(429, 60_000L, 5.0, telemetry, 100, 60_000L);
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h2", "/api");
        assertThat(handler.getQuarantineCount()).isEqualTo(2);
    }

    private TelemetryEmitter telemetry;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private CompositeEnforcementHandler handler;

    @BeforeEach
    void setUp() {
        telemetry = mock(TelemetryEmitter.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
    }

    @Test
    void throttleMapBoundedWhenOverMaxKeys() {
        handler = new CompositeEnforcementHandler(429, 60_000L, 1.0, telemetry, 2, 60_000L);
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String h = "hash" + i;
            hashes.add(h);
            boolean allowed = handler.tryAcquireThrottlePermit(h, "/api");
            assertThat(allowed).isTrue();
        }
        assertThat(handler.tryAcquireThrottlePermit(hashes.get(0), "/api")).isTrue();
    }

    @Test
    void quarantineBoundedMapDoesNotThrow() throws Exception {
        handler = new CompositeEnforcementHandler(429, 10_000L, 5.0, telemetry, 2, 60_000L);
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h2", "/api");
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h3", "/api");
        assertThat(handler.isQuarantined("h2", "/api")).isTrue();
        assertThat(handler.isQuarantined("h3", "/api")).isTrue();
    }

    @Test
    void identityGlobalScopeSharesThrottleAcrossEndpoints() {
        handler = new CompositeEnforcementHandler(429, 60_000L, 1.0, telemetry, 100, 60_000L, EnforcementScope.IDENTITY_GLOBAL);
        String id = "same-id";
        assertThat(handler.tryAcquireThrottlePermit(id, "/a")).isTrue();
        assertThat(handler.tryAcquireThrottlePermit(id, "/b")).isFalse();
    }

    @Test
    void identityEndpointScopeThrottlesPerEndpoint() {
        handler = new CompositeEnforcementHandler(429, 60_000L, 1.0, telemetry, 100, 60_000L, EnforcementScope.IDENTITY_ENDPOINT);
        String id = "same-id";
        assertThat(handler.tryAcquireThrottlePermit(id, "/a")).isTrue();
        assertThat(handler.tryAcquireThrottlePermit(id, "/b")).isTrue();
    }

    @Test
    void isQuarantinedExpiredEntryRemovedAtomically() throws Exception {
        handler = new CompositeEnforcementHandler(429, 50L, 5.0, telemetry, 100, 60_000L);
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        Thread.sleep(60);
        assertThat(handler.isQuarantined("h1", "/api")).isFalse();
        handler.apply(EnforcementAction.QUARANTINE, request, response, "h1", "/api");
        assertThat(handler.isQuarantined("h1", "/api")).isTrue();
    }

    @Test
    void localQuarantineStillAppliedWhenClusterWriterThrows() throws Exception {
        ClusterQuarantineWriter writer = mock(ClusterQuarantineWriter.class);
        doThrow(new RuntimeException("redis down")).when(writer).publishQuarantine(anyString(), anyString(), anyLong());
        handler = new CompositeEnforcementHandler(429, 60_000L, 5.0, telemetry, 100, 60_000L,
            EnforcementScope.IDENTITY_ENDPOINT, writer, "tenant-a");
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        boolean allowed = handler.apply(EnforcementAction.QUARANTINE, request, response, "id1", "/api");
        assertThat(allowed).isFalse();
        assertThat(handler.isQuarantined("id1", "/api")).isTrue();
        verify(writer).publishQuarantine(eq("tenant-a"), eq("id1|/api"), anyLong());
    }

    @Test
    void clusterWriterReceivesIdentityGlobalKey() throws Exception {
        ClusterQuarantineWriter writer = mock(ClusterQuarantineWriter.class);
        handler = new CompositeEnforcementHandler(429, 60_000L, 5.0, telemetry, 100, 60_000L,
            EnforcementScope.IDENTITY_GLOBAL, writer, "t");
        when(response.getWriter()).thenReturn(new java.io.PrintWriter(java.io.OutputStream.nullOutputStream()));
        handler.apply(EnforcementAction.QUARANTINE, request, response, "gh", "/x");
        verify(writer).publishQuarantine(eq("t"), eq("gh"), anyLong());
    }

    @Test
    void clusterThrottleRejectsBeforeLocalTokenBucket() {
        ClusterThrottleStore store = mock(ClusterThrottleStore.class);
        when(store.tryAcquire(eq("default"), eq("id|/api"))).thenReturn(false);
        handler = new CompositeEnforcementHandler(429, 60_000L, 1000.0, telemetry, 100, 60_000L,
            EnforcementScope.IDENTITY_ENDPOINT, NoopClusterQuarantineWriter.INSTANCE, store, "default");
        assertThat(handler.tryAcquireThrottlePermit("id", "/api")).isFalse();
        verify(store).tryAcquire(eq("default"), eq("id|/api"));
    }

    @Test
    void clusterThrottleWhenAllowsLocalThrottleStillApplies() {
        ClusterThrottleStore store = mock(ClusterThrottleStore.class);
        when(store.tryAcquire(anyString(), anyString())).thenReturn(true);
        handler = new CompositeEnforcementHandler(429, 60_000L, 1.0, telemetry, 100, 60_000L,
            EnforcementScope.IDENTITY_ENDPOINT, NoopClusterQuarantineWriter.INSTANCE, store, "t");
        assertThat(handler.tryAcquireThrottlePermit("x", "/a")).isTrue();
        assertThat(handler.tryAcquireThrottlePermit("x", "/a")).isFalse();
    }
}
