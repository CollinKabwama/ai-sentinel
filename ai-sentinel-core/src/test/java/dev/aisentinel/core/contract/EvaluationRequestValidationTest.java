package dev.aisentinel.core.contract;

import dev.aisentinel.core.identity.IdentityRiskSignalKeys;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationRequestValidationTest {

    @Test
    void missingCorrelationIdRejected() {
        assertThatThrownBy(() -> base().correlationId(" ").build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("correlationId");
    }

    @Test
    void blankMethodRejected() {
        assertThatThrownBy(() -> base().method("").build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("method");
    }

    @Test
    void malformedPathRejected() {
        assertThatThrownBy(() -> base().path("api/x").build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("path");
    }

    @Test
    void negativeTimestampRejected() {
        assertThatThrownBy(() -> base().timestampEpochMillis(-1).build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("timestamp");
    }

    @Test
    void nanTrustValueRejected() {
        assertThatThrownBy(() -> base()
            .trustSignals(Map.of(IdentityRiskSignalKeys.NEW_SESSION, Double.NaN))
            .build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("trustSignals");
    }

    @Test
    void infinityTrustValueRejected() {
        assertThatThrownBy(() -> base()
            .trustSignals(Map.of(IdentityRiskSignalKeys.IP_DRIFT, Double.POSITIVE_INFINITY))
            .build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("trustSignals");
    }

    @Test
    void unknownTrustKeyRejected() {
        assertThatThrownBy(() -> base()
            .trustSignals(Map.of("invented_signal", 0.5))
            .build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("unknown trustSignals");
    }

    @Test
    void oversizedAttributesRejected() {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (int i = 0; i < EvaluationContract.MAX_ATTRIBUTES + 1; i++) {
            attrs.put("k" + i, "v");
        }
        assertThatThrownBy(() -> base().attributes(attrs).build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("attributes");
    }

    @Test
    void nonLowercaseHeaderKeyRejected() {
        assertThatThrownBy(() -> base().headers(Map.of("Content-Type", "text/plain")).build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("lowercase");
    }

    @Test
    void attributesMapDefensivelyCopied() {
        Map<String, String> attrs = new HashMap<>();
        attrs.put("a", "1");
        EvaluationRequest request = base().attributes(attrs).build();
        attrs.put("b", "2");
        assertThat(request.attributes()).containsOnlyKeys("a");
        assertThatThrownBy(() -> request.attributes().put("c", "3"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void headersMapDefensivelyCopied() {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-length", "10");
        EvaluationRequest request = base().headers(headers).build();
        headers.put("user-agent", "x");
        assertThat(request.headers()).containsOnlyKeys("content-length");
    }

    @Test
    void requestHasNoActionOrFactorFields() {
        EvaluationRequest request = base().build();
        assertThat(request.getClass().getRecordComponents())
            .extracting(c -> c.getName())
            .doesNotContain("action", "factors", "advice", "evaluationStatuses", "mode");
    }

    private static EvaluationRequest.Builder base() {
        return EvaluationRequest.builder()
            .correlationId("corr-1")
            .identityKey("abc123hash")
            .path("/api/hello")
            .method("GET");
    }
}
