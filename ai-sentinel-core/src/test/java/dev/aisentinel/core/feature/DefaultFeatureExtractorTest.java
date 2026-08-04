package dev.aisentinel.core.feature;

import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.store.BaselineStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultFeatureExtractorTest {

    private DefaultFeatureExtractor extractor;
    private MapHttpRequestView request;

    @BeforeEach
    void setUp() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(1), 1000);
        extractor = new DefaultFeatureExtractor(store);
        request = new MapHttpRequestView().remoteAddr("192.168.1.1");
    }

    @Test
    void extractReturnsFeatures() {
        request.requestUri("/api/hello");

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
        request.requestUri("polygenelubricants");
        RequestFeatures f = ext.extract(request, "id1", new RequestContext());
        assertThat(f.endpoint()).isEqualTo("polygenelubricants");
        assertThat(f.toArray()).hasSize(7);
    }

    @Test
    void endpointHistoryEvictsWhenOverMaxKeys() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(1), 100_000);
        DefaultFeatureExtractor ext = new DefaultFeatureExtractor(store, 3, 60_000L);
        for (int i = 0; i < 5; i++) {
            request.requestUri("/api/" + i);
            ext.extract(request, "id" + i, new RequestContext());
        }
        request.requestUri("/api/0");
        RequestFeatures f = ext.extract(request, "id0", new RequestContext());
        assertThat(f.endpoint()).isEqualTo("/api/{id}");
    }

    @Test
    void pathParamsNormalizedToPreventMapExplosion() {
        request.requestUri("/api/users/12345");
        RequestFeatures f = extractor.extract(request, "hash1", new RequestContext());
        assertThat(f.endpoint()).isEqualTo("/api/users/{id}");
    }

    @Test
    void uuidPathParamNormalized() {
        request.requestUri("/api/orders/550e8400-e29b-41d4-a716-446655440000");
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
            request.requestUri("/api/hello").header("If-Match", "etag-value");

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

    @Test
    void parameterCountReflectsParameterMap() {
        request.requestUri("/api/search").parameter("q", "x").parameter("page", "2");
        RequestFeatures f = extractor.extract(request, "hash1", new RequestContext());
        assertThat(f.parameterCount()).isEqualTo(2);
    }
}
