package dev.aisentinel.core.feature;

import dev.aisentinel.core.http.MapHttpRequestView;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.store.BaselineStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultFeatureExtractorUnifiedClockTest {

    @Test
    void tokenAgeUsesSameExtractClockAsTimestampMillis() {
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 1000);
        DefaultFeatureExtractor extractor = new DefaultFeatureExtractor(store);
        long issuedSec = System.currentTimeMillis() / 1000L - 90L;
        MapHttpRequestView request = new MapHttpRequestView()
            .requestUri("/api")
            .header("Authorization", "Bearer x")
            .header("X-Token-Issued-At", Long.toString(issuedSec));

        var features = extractor.extract(request, "identity", new RequestContext());
        long impliedIssuedMs = features.timestampMillis() - (long) (features.tokenAgeSeconds() * 1000.0);
        assertThat(impliedIssuedMs / 1000L).isEqualTo(issuedSec);
    }
}
