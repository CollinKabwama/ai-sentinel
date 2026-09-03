package dev.aisentinel.core.contract;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationContractHardeningTest {

    @Test
    void contractHttpRequestViewParameterArraysCannotBeMutatedThroughAccessor() {
        EvaluationRequest request = EvaluationRequest.builder()
            .correlationId("corr")
            .identityKey("id")
            .path("/api")
            .parameters(Map.of("q", "safe"))
            .build();
        ContractHttpRequestView view = new ContractHttpRequestView(request);

        view.getParameterMap().get("q")[0] = "mutated";

        assertThat(view.getParameterMap().get("q")[0]).isEqualTo("safe");
    }

    @Test
    void controlCharactersRejectedFromCorrelationId() {
        assertThatThrownBy(() -> EvaluationRequest.builder()
            .correlationId("corr\nInjected: true")
            .identityKey("id")
            .path("/api")
            .build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("correlationId");
    }

    @Test
    void controlCharactersRejectedFromPathAndRemoteAddress() {
        assertThatThrownBy(() -> EvaluationRequest.builder()
            .correlationId("corr")
            .identityKey("id")
            .path("/api\nx")
            .build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("path");

        assertThatThrownBy(() -> EvaluationRequest.builder()
            .correlationId("corr")
            .identityKey("id")
            .path("/api")
            .remoteAddress("127.0.0.1\nspoof")
            .build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("remoteAddress");
    }

    @Test
    void controlCharactersRejectedFromMaps() {
        assertThatThrownBy(() -> EvaluationRequest.builder()
            .correlationId("corr")
            .identityKey("id")
            .path("/api")
            .headers(Map.of("x-safe", "ok\nbad"))
            .build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("headers value");

        assertThatThrownBy(() -> EvaluationRequest.builder()
            .correlationId("corr")
            .identityKey("id")
            .path("/api")
            .attributes(Map.of("action\nspoof", "BLOCK"))
            .build())
            .isInstanceOf(EvaluationContractException.class)
            .hasMessageContaining("attributes key");
    }
}
