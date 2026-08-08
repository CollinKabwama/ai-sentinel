package dev.aisentinel.core.regression;

import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.enforcement.DiscardingEnforcementResponse;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.enforcement.EnforcementScope;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.store.BaselineStore;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import dev.aisentinel.core.telemetry.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Request-path feature and enforcement semantics: token age, parameter count,
 * committed-response writes, and enforcement-scope blast radius.
 */
class RequestPathSemanticsRegressionTest {

    @Test
    void tokenAge_missingInvalidFutureAndValidAreDistinct() {
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(1), 100));
        long nowSec = System.currentTimeMillis() / 1000L;

        assertThat(age(extractor, null, null)).isEqualTo(-1.0);
        assertThat(age(extractor, "Bearer x", null)).isEqualTo(-1.0);
        assertThat(age(extractor, "Bearer x", "not-a-number")).isEqualTo(-1.0);
        assertThat(age(extractor, "   ", String.valueOf(nowSec - 10))).isEqualTo(-1.0);

        double past = age(extractor, "Bearer x", String.valueOf(nowSec - 120));
        assertThat(past).isBetween(119.0, 125.0);

        // Within tolerated clock skew (ordinary issuer/application skew): clamp to 0, distinct from -1.
        double futureWithinSkew = age(extractor, "Bearer x", String.valueOf(nowSec + 30));
        assertThat(futureWithinSkew).isEqualTo(0.0);
        assertThat(futureWithinSkew).isNotEqualTo(-1.0);

        // Materially future (1 hour): beyond tolerated skew — treat as missing/invalid (-1),
        // not freshly issued (0), so a spoofed far-future timestamp cannot neutralize this feature.
        double futureBeyondSkew = age(extractor, "Bearer x", String.valueOf(nowSec + 3600));
        assertThat(futureBeyondSkew).isEqualTo(-1.0);

        double extreme = age(extractor, "Bearer x", "9999999999999999999");
        assertThat(extreme).isEqualTo(-1.0);
    }

    @Test
    void parameterCount_isQueryFormMapSize_notJsonBody() {
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(1), 100));

        MapHttpRequestView form = new MapHttpRequestView()
            .requestUri("/api/search")
            .parameter("q", "x")
            .parameter("page", "1");
        assertThat(extractor.extract(form, "id", new RequestContext()).parameterCount()).isEqualTo(2);

        // JSON-style POST with Content-Type/Length but no servlet parameter map entries → 0.
        MapHttpRequestView json = new MapHttpRequestView()
            .requestUri("/api/orders")
            .header("Content-Type", "application/json")
            .header("Content-Length", "256");
        RequestFeatures f = extractor.extract(json, "id", new RequestContext());
        assertThat(f.parameterCount()).isEqualTo(0);
        assertThat(f.payloadSizeBytes()).isEqualTo(256);
    }

    @Test
    void committedResponse_skipsHttpWrite_butAppliesQuarantineAndTelemetry() {
        List<TelemetryEvent> events = new ArrayList<>();
        TelemetryEmitter telemetry = events::add;
        CompositeEnforcementHandler handler = new CompositeEnforcementHandler(429, 60_000L, 5.0, telemetry);
        RecordingResponse committed = new RecordingResponse(true);

        boolean proceed = handler.apply(
            EnforcementAction.QUARANTINE, mock(HttpRequestView.class), committed, "id-commit", "/api/a");

        assertThat(proceed).isFalse();
        assertThat(committed.statusWrites.get()).isZero();
        assertThat(committed.bodyWrites.get()).isZero();
        assertThat(handler.isQuarantined("id-commit", "/api/a")).isTrue();
        assertThat(events).isNotEmpty();
    }

    @Test
    void uncommittedResponse_writesBlockStatusAndBody() {
        CompositeEnforcementHandler handler = new CompositeEnforcementHandler(403, 60_000L, 5.0, mock(TelemetryEmitter.class));
        RecordingResponse open = new RecordingResponse(false);
        handler.apply(EnforcementAction.BLOCK, mock(HttpRequestView.class), open, "id", "/api");
        assertThat(open.statusWrites.get()).isEqualTo(1);
        assertThat(open.lastStatus.get()).isEqualTo(403);
        assertThat(open.bodyWrites.get()).isEqualTo(1);
    }

    @Test
    void committedThrottleDenial_skipsWrite_stillDenies() {
        CompositeEnforcementHandler handler = new CompositeEnforcementHandler(429, 60_000L, 1.0, mock(TelemetryEmitter.class));
        assertThat(handler.tryAcquireThrottlePermit("id-th", "/api")).isTrue();
        RecordingResponse committed = new RecordingResponse(true);
        boolean proceed = handler.apply(
            EnforcementAction.THROTTLE, mock(HttpRequestView.class), committed, "id-th", "/api");
        assertThat(proceed).isFalse();
        assertThat(committed.statusWrites.get()).isZero();
    }

    @Test
    void enforcementScope_globalQuarantineCoversOtherEndpoints() {
        CompositeEnforcementHandler global = new CompositeEnforcementHandler(
            429, 60_000L, 5.0, mock(TelemetryEmitter.class), 100, 60_000L, EnforcementScope.IDENTITY_GLOBAL);
        global.apply(EnforcementAction.QUARANTINE, mock(HttpRequestView.class),
            DiscardingEnforcementResponse.INSTANCE, "same", "/api/a");
        assertThat(global.isQuarantined("same", "/api/a")).isTrue();
        assertThat(global.isQuarantined("same", "/api/b")).isTrue();
    }

    @Test
    void enforcementScope_endpointQuarantineDoesNotCoverOtherEndpoints() {
        CompositeEnforcementHandler scoped = new CompositeEnforcementHandler(
            429, 60_000L, 5.0, mock(TelemetryEmitter.class), 100, 60_000L, EnforcementScope.IDENTITY_ENDPOINT);
        scoped.apply(EnforcementAction.QUARANTINE, mock(HttpRequestView.class),
            DiscardingEnforcementResponse.INSTANCE, "same", "/api/a");
        assertThat(scoped.isQuarantined("same", "/api/a")).isTrue();
        assertThat(scoped.isQuarantined("same", "/api/b")).isFalse();
    }

    @Test
    void monitorDiscardingResponse_neverCommitsWrites() {
        CompositeEnforcementHandler handler = new CompositeEnforcementHandler(429, 60_000L, 5.0, mock(TelemetryEmitter.class));
        assertThat(DiscardingEnforcementResponse.INSTANCE.isCommitted()).isFalse();
        handler.apply(EnforcementAction.BLOCK, mock(HttpRequestView.class),
            DiscardingEnforcementResponse.INSTANCE, "id", "/api");
    }

    private static double age(DefaultFeatureExtractor extractor, String auth, String issuedAt) {
        MapHttpRequestView req = new MapHttpRequestView().requestUri("/api");
        if (auth != null) {
            req.header("Authorization", auth);
        }
        if (issuedAt != null) {
            req.header("X-Token-Issued-At", issuedAt);
        }
        return extractor.extract(req, "id", new RequestContext()).tokenAgeSeconds();
    }

    private static final class RecordingResponse implements EnforcementResponse {
        private final boolean committed;
        final AtomicInteger statusWrites = new AtomicInteger();
        final AtomicInteger bodyWrites = new AtomicInteger();
        final AtomicInteger lastStatus = new AtomicInteger();
        final AtomicBoolean contentTypeSet = new AtomicBoolean();

        RecordingResponse(boolean committed) {
            this.committed = committed;
        }

        @Override
        public void setStatus(int statusCode) {
            statusWrites.incrementAndGet();
            lastStatus.set(statusCode);
        }

        @Override
        public void setContentType(String contentType) {
            contentTypeSet.set(true);
        }

        @Override
        public void writeBody(String body) throws IOException {
            bodyWrites.incrementAndGet();
        }

        @Override
        public boolean isCommitted() {
            return committed;
        }
    }
}
