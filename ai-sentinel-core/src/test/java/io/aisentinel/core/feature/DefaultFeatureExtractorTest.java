package io.aisentinel.core.feature;

import io.aisentinel.core.model.RequestContext;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.store.BaselineStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefaultFeatureExtractorTest {

    private DefaultFeatureExtractor extractor;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(1), 1000);
        extractor = new DefaultFeatureExtractor(store);
        request = mock(HttpServletRequest.class);
    }

    @Test
    void extractReturnsFeatures() {
        when(request.getRequestURI()).thenReturn("/api/hello");
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getParameterMap()).thenReturn(Collections.emptyMap());
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getHeader("Content-Length")).thenReturn(null);

        RequestFeatures f = extractor.extract(request, "hash123", new RequestContext());

        assertThat(f.identityHash()).isEqualTo("hash123");
        assertThat(f.endpoint()).isEqualTo("/api/hello");
        assertThat(f.parameterCount()).isEqualTo(0);
        assertThat(f.toArray()).hasSize(7);
    }

    @Test
    void endpointWithHashCodeIntegerMinValueDoesNotThrow() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(1), 1000);
        DefaultFeatureExtractor ext = new DefaultFeatureExtractor(store, 1000, 60_000L);
        when(request.getRequestURI()).thenReturn("polygenelubricants");
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getParameterMap()).thenReturn(Collections.emptyMap());
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getHeader("Content-Length")).thenReturn(null);
        RequestFeatures f = ext.extract(request, "id1", new RequestContext());
        assertThat(f.endpoint()).isEqualTo("polygenelubricants");
        assertThat(f.toArray()).hasSize(7);
    }

    @Test
    void endpointHistoryEvictsWhenOverMaxKeys() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(1), 100_000);
        DefaultFeatureExtractor ext = new DefaultFeatureExtractor(store, 3, 60_000L);
        when(request.getParameterMap()).thenReturn(Collections.emptyMap());
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getHeader("Content-Length")).thenReturn(null);
        for (int i = 0; i < 5; i++) {
            when(request.getRequestURI()).thenReturn("/api/" + i);
            when(request.getRemoteAddr()).thenReturn("192.168.1.1");
            ext.extract(request, "id" + i, new RequestContext());
        }
        when(request.getRequestURI()).thenReturn("/api/0");
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        RequestFeatures f = ext.extract(request, "id0", new RequestContext());
        assertThat(f.endpoint()).isEqualTo("/api/{id}");
    }

    @Test
    void pathParamsNormalizedToPreventMapExplosion() {
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getParameterMap()).thenReturn(Collections.emptyMap());
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getHeader("Content-Length")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/users/12345");
        RequestFeatures f = extractor.extract(request, "hash1", new RequestContext());
        assertThat(f.endpoint()).isEqualTo("/api/users/{id}");
    }

    @Test
    void uuidPathParamNormalized() {
        when(request.getRemoteAddr()).thenReturn("192.168.1.1");
        when(request.getParameterMap()).thenReturn(Collections.emptyMap());
        when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        when(request.getHeader("Content-Length")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/orders/550e8400-e29b-41d4-a716-446655440000");
        RequestFeatures f = extractor.extract(request, "hash1", new RequestContext());
        assertThat(f.endpoint()).isEqualTo("/api/orders/{id}");
    }

    @Test
    void normalizePathParamsStaticMethod() {
        assertThat(DefaultFeatureExtractor.normalizePathParams("/api/users/123")).isEqualTo("/api/users/{id}");
        assertThat(DefaultFeatureExtractor.normalizePathParams("/api/items/abc")).isEqualTo("/api/items/abc");
    }

    @Test
    void headerFingerprintIsLocaleInvariant() {
        Locale old = Locale.getDefault();
        try {
            when(request.getRequestURI()).thenReturn("/api/hello");
            when(request.getRemoteAddr()).thenReturn("192.168.1.1");
            when(request.getParameterMap()).thenReturn(Collections.emptyMap());
            when(request.getHeaderNames()).thenAnswer(inv -> Collections.enumeration(List.of("If-Match")));
            when(request.getHeader("If-Match")).thenReturn("etag-value");
            when(request.getHeader("Content-Length")).thenReturn(null);

            Locale.setDefault(Locale.US);
            RequestFeatures us = new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(1), 1000))
                .extract(request, "hash1", new RequestContext());

            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            RequestFeatures tr = new DefaultFeatureExtractor(new BaselineStore(Duration.ofMinutes(1), 1000))
                .extract(request, "hash1", new RequestContext());

            assertThat(tr.headerFingerprintHash()).isEqualTo(us.headerFingerprintHash());
        } finally {
            Locale.setDefault(old);
        }
    }
}
