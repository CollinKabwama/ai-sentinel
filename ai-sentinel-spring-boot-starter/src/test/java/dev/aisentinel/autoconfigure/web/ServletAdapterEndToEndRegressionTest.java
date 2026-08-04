package dev.aisentinel.autoconfigure.web;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.enforcement.CompositeEnforcementHandler;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.store.BaselineStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression through the real servlet adapters introduced by the architectural refactor:
 * {@link ServletHttpRequestView} + {@link ServletEnforcementResponse} + {@link SentinelFilter}.
 */
class ServletAdapterEndToEndRegressionTest {

    private static final class FixedScorer implements AnomalyScorer {
        private final AtomicReference<Double> value = new AtomicReference<>(0.0);

        void set(double v) {
            value.set(v);
        }

        @Override
        public double score(RequestFeatures features) {
            return value.get();
        }

        @Override
        public void update(RequestFeatures features) {
        }
    }

    private FixedScorer scorer;
    private SentinelPipeline pipeline;
    private SentinelProperties props;
    private final AtomicInteger failOpen = new AtomicInteger();

    @BeforeEach
    void setUp() {
        scorer = new FixedScorer();
        pipeline = new SentinelPipeline(
            new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(5), 10_000)),
            scorer,
            new ThresholdPolicyEngine(),
            new CompositeEnforcementHandler(429, 60_000L, 1000.0, e -> {}, 1000, 60_000L),
            e -> {},
            StartupGrace.NEVER,
            new SentinelMetrics() {
                @Override
                public void recordFailOpen() {
                    failOpen.incrementAndGet();
                }
            }
        );
        props = new SentinelProperties();
        props.setEnabled(true);
        props.setMode(SentinelProperties.Mode.ENFORCE);
        props.setExcludePaths(java.util.List.of("/actuator/**", "/health"));
    }

    private SentinelFilter filter() {
        return new SentinelFilter(pipeline, props, new SentinelMetrics() {
            @Override
            public void recordFailOpen() {
                failOpen.incrementAndGet();
            }
        });
    }

    @Test
    void normalRequest_adapterPathAllowsAndContinuesChain() throws Exception {
        scorer.set(0.0);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/hello");
        request.setRemoteAddr("198.51.100.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (ServletRequest req, ServletResponse res) -> chainCalled.set(true);

        filter().doFilter(request, response, chain);

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void elevatedRisk_blockDoesNotContinueChainAndWritesStatus() throws Exception {
        scorer.set(0.7);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/secure");
        request.setRemoteAddr("198.51.100.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (ServletRequest req, ServletResponse res) -> chainCalled.set(true);

        filter().doFilter(request, response, chain);

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).isNotBlank();
    }

    @Test
    void quarantine_criticalScoreStopsChain() throws Exception {
        scorer.set(0.95);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin");
        request.setRemoteAddr("198.51.100.3");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).containsIgnoringCase("quarantine");
    }

    @Test
    void monitorMode_continuesChainEvenWhenPipelineWouldBlock() throws Exception {
        props.setMode(SentinelProperties.Mode.MONITOR);
        scorer.set(0.95);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/secure");
        request.setRemoteAddr("198.51.100.4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
    }

    @Test
    void excludedPath_bypassesPipeline() throws Exception {
        scorer.set(0.95);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void modeOff_bypassesPipeline() throws Exception {
        props.setMode(SentinelProperties.Mode.OFF);
        scorer.set(0.95);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/secure");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter().doFilter(request, response, (req, res) -> chainCalled.set(true));

        assertThat(chainCalled).isTrue();
    }

    @Test
    void servletView_preservesUriMethodAndRemoteAddrIntoFeaturePath() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders/42");
        request.setRemoteAddr("203.0.113.55");
        request.addHeader("Content-Length", "8");
        request.addParameter("sku", "abc");
        ServletHttpRequestView view = new ServletHttpRequestView(request);

        assertThat(view.getMethod()).isEqualTo("POST");
        assertThat(view.getRequestURI()).isEqualTo("/api/orders/42");
        assertThat(view.getRemoteAddr()).isEqualTo("203.0.113.55");
        assertThat(view.getHeader("Content-Length")).isEqualTo("8");
        assertThat(view.getParameterMap()).containsKey("sku");
    }

    @Test
    void servletEnforcementResponse_writesStatusAndBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletEnforcementResponse adapter = new ServletEnforcementResponse(response);
        adapter.setStatus(403);
        adapter.setContentType("text/plain;charset=UTF-8");
        adapter.writeBody("Forbidden");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getContentAsString()).isEqualTo("Forbidden");
        assertThat(adapter.getServletResponse()).isSameAs(response);
    }

    @Test
    void servletView_sessionMethodsReflectContainerSession() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/s");
        assertThat(new ServletHttpRequestView(request).hasSession()).isFalse();
        assertThat(new ServletHttpRequestView(request).isNewSession()).isFalse();
        assertThat(new ServletHttpRequestView(request).getSessionId()).isNull();
        assertThat(new ServletHttpRequestView(request).getSessionCreationTimeMillis()).isZero();
        assertThat(new ServletHttpRequestView(request).getSessionLastAccessedTimeMillis()).isZero();

        request.getSession(true);
        ServletHttpRequestView view = new ServletHttpRequestView(request);
        assertThat(view.hasSession()).isTrue();
        assertThat(view.isNewSession()).isTrue();
        assertThat(view.getSessionId()).isNotBlank();
        assertThat(view.getSessionCreationTimeMillis()).isPositive();
        assertThat(view.getSessionLastAccessedTimeMillis()).isPositive();
        assertThat(view.getServletRequest()).isSameAs(request);
    }

    @Test
    void servletView_existingSession_isNotNew() {
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/s");
        first.setSession(first.getSession(true));
        // Simulate a subsequent request with the same session: MockHttpSession.isNew() becomes false
        // after the session has been accessed / marked accessed.
        org.springframework.mock.web.MockHttpSession session =
            (org.springframework.mock.web.MockHttpSession) first.getSession(false);
        session.setNew(false);

        MockHttpServletRequest next = new MockHttpServletRequest("GET", "/api/s");
        next.setSession(session);
        ServletHttpRequestView view = new ServletHttpRequestView(next);
        assertThat(view.hasSession()).isTrue();
        assertThat(view.isNewSession()).isFalse();
        assertThat(view.getSessionId()).isEqualTo(session.getId());
    }

    @Test
    void monitorMode_customHandlerCannotWriteClientResponse() throws Exception {
        props.setMode(SentinelProperties.Mode.MONITOR);
        scorer.set(0.95);
        SentinelPipeline writingPipeline = new SentinelPipeline(
            new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(5), 10_000)),
            scorer,
            new ThresholdPolicyEngine(),
            (action, request, response, identityHash, endpoint) -> {
                try {
                    response.setStatus(429);
                    response.setContentType("text/plain");
                    response.writeBody("should-not-reach-client");
                } catch (Exception ignored) {
                }
                return false;
            },
            e -> {},
            StartupGrace.NEVER,
            SentinelMetrics.NOOP
        );
        SentinelFilter monitorFilter = new SentinelFilter(writingPipeline, props, SentinelMetrics.NOOP);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/secure");
        request.setRemoteAddr("198.51.100.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        monitorFilter.doFilter(request, response, (req, res) -> {
            chainCalled.set(true);
            ((MockHttpServletResponse) res).setStatus(200);
            ((MockHttpServletResponse) res).getWriter().write("ok");
        });

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("ok");
    }
}
