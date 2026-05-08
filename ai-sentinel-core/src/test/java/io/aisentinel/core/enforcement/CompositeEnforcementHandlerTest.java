package io.aisentinel.core.enforcement;

import io.aisentinel.core.policy.EnforcementAction;
import io.aisentinel.core.telemetry.TelemetryEmitter;
import io.aisentinel.core.telemetry.TelemetryEvent;
import io.aisentinel.distributed.quarantine.ClusterQuarantineWriter;
import io.aisentinel.distributed.quarantine.NoopClusterQuarantineWriter;
import io.aisentinel.distributed.throttle.ClusterThrottleStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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

    @Test
    void blockUsesConfiguredBlockStatusCode() throws Exception {
        handler = new CompositeEnforcementHandler(503, 60_000L, 5.0, telemetry, 100, 60_000L);
        when(response.getWriter()).thenReturn(new PrintWriter(java.io.OutputStream.nullOutputStream()));
        boolean allowed = handler.apply(EnforcementAction.BLOCK, request, response, "hashhash12", "/api");
        assertThat(allowed).isFalse();
        verify(response).setStatus(503);
        verify(telemetry).emit(argThat(e -> policyActionWithDetail(e, "BLOCK", "503")));
    }

    @Test
    void throttleResponseUses429EvenWhenBlockStatusCodeDiffers() throws Exception {
        handler = new CompositeEnforcementHandler(403, 60_000L, 1.0, telemetry, 100, 60_000L);
        when(response.getWriter()).thenReturn(new PrintWriter(java.io.OutputStream.nullOutputStream()));
        assertThat(handler.apply(EnforcementAction.THROTTLE, request, response, "idididid12", "/api")).isTrue();
        assertThat(handler.apply(EnforcementAction.THROTTLE, request, response, "idididid12", "/api")).isFalse();
        verify(response).setStatus(429);
        verify(response, never()).setStatus(403);
    }

    @Test
    void throttleEmitsTelemetryWhenResponseWriterFails() throws Exception {
        handler = new CompositeEnforcementHandler(429, 60_000L, 1.0, telemetry, 100, 60_000L);
        when(response.getWriter()).thenThrow(new IOException("response committed"));
        assertThat(handler.apply(EnforcementAction.THROTTLE, request, response, "idididid12", "/api")).isTrue();
        assertThat(handler.apply(EnforcementAction.THROTTLE, request, response, "idididid12", "/api")).isFalse();
        verify(telemetry).emit(argThat(e -> policyActionWithDetail(e, "THROTTLE_APPLIED", "429")));
    }

    @Test
    void blockEmitsTelemetryWhenResponseWriterFails() throws Exception {
        handler = new CompositeEnforcementHandler(429, 60_000L, 5.0, telemetry, 100, 60_000L);
        when(response.getWriter()).thenThrow(new IOException("broken pipe"));
        boolean allowed = handler.apply(EnforcementAction.BLOCK, request, response, "hashhash12", "/api");
        assertThat(allowed).isFalse();
        verify(telemetry).emit(argThat(e -> policyActionWithDetail(e, "BLOCK", "429")));
    }

    @Test
    void quarantineEmitsTelemetryWhenResponseWriterFails() throws Exception {
        handler = new CompositeEnforcementHandler(429, 60_000L, 5.0, telemetry, 100, 60_000L);
        when(response.getWriter()).thenThrow(new IOException("broken pipe"));
        boolean allowed = handler.apply(EnforcementAction.QUARANTINE, request, response, "hashhash12", "/api");
        assertThat(allowed).isFalse();
        verify(telemetry).emit(argThat(e -> "QuarantineStarted".equals(e.type())
            && Long.valueOf(60_000L).equals(e.payload().get("durationMs"))));
    }

    @Test
    void applyAllowReturnsTrueWithoutPolicyTelemetry() {
        handler = new CompositeEnforcementHandler(429, 60_000L, 5.0, telemetry, 100, 60_000L);
        assertThat(handler.apply(EnforcementAction.ALLOW, request, response, "hashhash12", "/api")).isTrue();
        verifyNoInteractions(telemetry);
    }

    @Test
    void applyMonitorEmitsAndReturnsTrue() {
        handler = new CompositeEnforcementHandler(429, 60_000L, 5.0, telemetry, 100, 60_000L);
        assertThat(handler.apply(EnforcementAction.MONITOR, request, response, "hashhash12", "/api")).isTrue();
        verify(telemetry).emit(argThat(e -> policyActionWithDetail(e, "MONITOR", null)));
    }

    @Test
    void block403WritesForbiddenBody() throws Exception {
        handler = new CompositeEnforcementHandler(403, 60_000L, 5.0, telemetry, 100, 60_000L);
        java.io.StringWriter sw = new java.io.StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));
        handler.apply(EnforcementAction.BLOCK, request, response, "hashhash12", "/api");
        assertThat(sw.toString()).isEqualTo("Forbidden");
    }

    @Test
    void blockNon403WritesTooManyRequestsBody() throws Exception {
        handler = new CompositeEnforcementHandler(503, 60_000L, 5.0, telemetry, 100, 60_000L);
        java.io.StringWriter sw = new java.io.StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(sw));
        handler.apply(EnforcementAction.BLOCK, request, response, "hashhash12", "/api");
        assertThat(sw.toString()).isEqualTo("Too Many Requests");
    }

    @Test
    void getThrottleCountTracksDistinctKeys() {
        handler = new CompositeEnforcementHandler(429, 60_000L, 1.0, telemetry, 100, 60_000L);
        handler.tryAcquireThrottlePermit("aaaaaaaa12", "/a");
        handler.tryAcquireThrottlePermit("bbbbbbbb12", "/a");
        assertThat(handler.getThrottleCount()).isEqualTo(2);
    }

    @Test
    void quarantineWithEmptyEndpointUsesIdentityPipeKey() throws Exception {
        ClusterQuarantineWriter writer = mock(ClusterQuarantineWriter.class);
        handler = new CompositeEnforcementHandler(429, 60_000L, 5.0, telemetry, 100, 60_000L,
            EnforcementScope.IDENTITY_ENDPOINT, writer, "tenant-z");
        when(response.getWriter()).thenReturn(new PrintWriter(java.io.OutputStream.nullOutputStream()));
        handler.apply(EnforcementAction.QUARANTINE, request, response, "qqqqqqqq12", "");
        verify(writer).publishQuarantine(eq("tenant-z"), eq("qqqqqqqq12|"), anyLong());
        assertThat(handler.isQuarantined("qqqqqqqq12", "")).isTrue();
    }

    @Test
    void clusterThrottleGlobalScopeUsesIdentityOnlyKey() {
        ClusterThrottleStore store = mock(ClusterThrottleStore.class);
        when(store.tryAcquire(eq("tid"), eq("global-id"))).thenReturn(true);
        handler = new CompositeEnforcementHandler(429, 60_000L, 1.0, telemetry, 100, 60_000L,
            EnforcementScope.IDENTITY_GLOBAL, NoopClusterQuarantineWriter.INSTANCE, store, "tid");
        assertThat(handler.tryAcquireThrottlePermit("global-id", "/any")).isTrue();
        verify(store).tryAcquire(eq("tid"), eq("global-id"));
    }

    @Test
    void throttleAppliedWhenClusterAllowsButLocalBucketExhausted() throws Exception {
        ClusterThrottleStore store = mock(ClusterThrottleStore.class);
        when(store.tryAcquire(anyString(), anyString())).thenReturn(true);
        handler = new CompositeEnforcementHandler(429, 60_000L, 1.0, telemetry, 100, 60_000L,
            EnforcementScope.IDENTITY_ENDPOINT, NoopClusterQuarantineWriter.INSTANCE, store, "tid");
        when(response.getWriter()).thenReturn(new PrintWriter(java.io.OutputStream.nullOutputStream()));
        assertThat(handler.apply(EnforcementAction.THROTTLE, request, response, "zzzzzzzz12", "/api")).isTrue();
        assertThat(handler.apply(EnforcementAction.THROTTLE, request, response, "zzzzzzzz12", "/api")).isFalse();
        verify(telemetry).emit(argThat(e -> policyActionWithDetail(e, "THROTTLE_APPLIED", "429")));
    }

    private static boolean policyActionWithDetail(TelemetryEvent e, String action, String detail) {
        if (!"PolicyActionApplied".equals(e.type())) {
            return false;
        }
        if (!action.equals(e.payload().get("action"))) {
            return false;
        }
        Object d = e.payload().get("detail");
        return detail == null ? d == null : detail.equals(d);
    }
}
