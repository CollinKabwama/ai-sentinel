package dev.aisentinel.autoconfigure.evaluation;

import dev.aisentinel.autoconfigure.web.RemoteEvaluationController;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.contract.LocalEvaluationBridge;
import dev.aisentinel.core.contract.LocalEvaluationExecutor;
import dev.aisentinel.core.decision.SentinelDecisionEngine;
import dev.aisentinel.core.enforcement.EnforcementHandler;
import dev.aisentinel.core.enforcement.EnforcementResponse;
import dev.aisentinel.core.feature.FeatureExtractor;
import dev.aisentinel.core.fusion.NoopRequestRiskFusion;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.identity.spi.NoopTrustEvaluator;
import dev.aisentinel.core.metrics.SentinelMetrics;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.policy.EnforcementAction;
import dev.aisentinel.core.policy.NoopTrustPolicyAdjuster;
import dev.aisentinel.core.policy.ThresholdPolicyEngine;
import dev.aisentinel.core.runtime.StartupGrace;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.telemetry.TelemetryEmitter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RemoteEvaluationControllerTest {

    private static final String API_KEY = "test-eval-key-12345678";

    private MockMvc mockMvc;
    private ObjectMapper mapper;
    private AtomicInteger evaluations;

    @BeforeEach
    void setUp() {
        evaluations = new AtomicInteger();
        AnomalyScorer scorer = new AnomalyScorer() {
            @Override
            public double score(RequestFeatures features) {
                evaluations.incrementAndGet();
                return 0.05;
            }

            @Override
            public void update(RequestFeatures features) {
            }
        };
        LocalEvaluationExecutor executor = new LocalEvaluationExecutor(
            new LocalEvaluationBridge(fixedExtractor("id"), engine(scorer)));
        SentinelProperties props = new SentinelProperties();
        props.getEvaluation().getServer().setEnabled(true);
        props.getEvaluation().getServer().setApiKey(API_KEY);
        props.getEvaluation().getServer().setMaxRequestBytes(262_144);
        mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new RemoteEvaluationController(executor, props)).build();
    }

    @Test
    void missingAuthRejected() throws Exception {
        mockMvc.perform(post(RemoteEvaluationController.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsBytes(request("c1"))))
            .andExpect(status().isUnauthorized());
        assertThat(evaluations.get()).isZero();
    }

    @Test
    void badAuthRejected() throws Exception {
        mockMvc.perform(post(RemoteEvaluationController.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(RemoteEvaluationConstants.API_KEY_HEADER, "wrong")
                .content(mapper.writeValueAsBytes(request("c2"))))
            .andExpect(status().isUnauthorized());
        assertThat(evaluations.get()).isZero();
    }

    @Test
    void authenticatedEvaluationReturnsAllow() throws Exception {
        mockMvc.perform(post(RemoteEvaluationController.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(RemoteEvaluationConstants.API_KEY_HEADER, API_KEY)
                .content(mapper.writeValueAsBytes(request("c3"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.action").value("ALLOW"))
            .andExpect(jsonPath("$.correlationId").value("c3"))
            .andExpect(jsonPath("$.anomalyScore").value(0.05));
        assertThat(evaluations.get()).isEqualTo(1);
    }

    @Test
    void malformedJsonRejected() throws Exception {
        mockMvc.perform(post(RemoteEvaluationController.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(RemoteEvaluationConstants.API_KEY_HEADER, API_KEY)
                .content("{not-json"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedContractVersionRejected() throws Exception {
        String body = """
            {"contractVersion":99,"correlationId":"c","timestampEpochMillis":1,"method":"GET","path":"/api",
            "identityKey":"id","sessionPresent":false,"sessionNew":false,"headers":{},"parameters":{},
            "attributes":{},"trustSignals":{}}
            """;
        mockMvc.perform(post(RemoteEvaluationController.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(RemoteEvaluationConstants.API_KEY_HEADER, API_KEY)
                .content(body))
            .andExpect(status().isBadRequest());
    }

    @Test
    void modeSpoofAttributesDoNotChangeServerDecision() throws Exception {
        EvaluationRequest spoofed = EvaluationRequest.builder()
            .correlationId("spoof")
            .identityKey("id")
            .path("/api")
            .attributes(java.util.Map.of("mode", "OFF", "action", "ALLOW"))
            .build();
        mockMvc.perform(post(RemoteEvaluationController.PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(RemoteEvaluationConstants.API_KEY_HEADER, API_KEY)
                .content(mapper.writeValueAsBytes(spoofed)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.action").value("ALLOW"));
    }

    private static EvaluationRequest request(String corr) {
        return EvaluationRequest.builder()
            .correlationId(corr)
            .identityKey("id")
            .path("/api/hello")
            .method("GET")
            .build();
    }

    private static FeatureExtractor fixedExtractor(String identity) {
        return (request, identityHash, ctx) -> RequestFeatures.builder()
            .identityHash(identity)
            .endpoint(request.getRequestURI())
            .timestampMillis(1L)
            .requestsPerWindow(1)
            .endpointEntropy(0.1)
            .endpointConcentration(0.1)
            .tokenAgeSeconds(-1)
            .parameterCount(0)
            .payloadSizeBytes(0)
            .headerFingerprintHash(1L)
            .ipBucket(1)
            .build();
    }

    private static SentinelDecisionEngine engine(AnomalyScorer scorer) {
        return new SentinelDecisionEngine(
            scorer,
            new ThresholdPolicyEngine(0.2, 0.4, 0.6, 0.8),
            new EnforcementHandler() {
                @Override
                public boolean apply(EnforcementAction action, HttpRequestView request, EnforcementResponse response,
                                     String identityHash, String endpoint) {
                    return true;
                }

                @Override
                public boolean isQuarantined(String identityHash, String endpoint) {
                    return false;
                }
            },
            (TelemetryEmitter) event -> {
            },
            StartupGrace.NEVER,
            SentinelMetrics.NOOP,
            NoopTrustEvaluator.INSTANCE,
            NoopTrustPolicyAdjuster.INSTANCE,
            NoopRequestRiskFusion.INSTANCE);
    }
}
