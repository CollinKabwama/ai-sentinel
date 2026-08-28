package dev.aisentinel.autoconfigure.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aisentinel.core.contract.EvaluationContractException;
import dev.aisentinel.core.contract.EvaluationFailureResponses;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import dev.aisentinel.core.contract.EvaluationResponseValidator;
import dev.aisentinel.core.metrics.FailOpenReason;
import dev.aisentinel.core.metrics.RemoteEvaluationOutcome;
import dev.aisentinel.core.metrics.SentinelMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/**
 * Authenticated HTTP client for remote evaluation.
 * <p>
 * No automatic retries (evaluation may mutate server baseline/quarantine state).
 * Transport failures yield {@link EvaluationFailureResponses#remoteFailure(String)} — not fabricated risk.
 */
public final class RemoteEvaluationClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteEvaluationClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final SentinelMetrics metrics;
    private final String apiKey;
    private final String evaluationPath;

    public RemoteEvaluationClient(String baseUrl,
                                  String evaluationPath,
                                  String apiKey,
                                  Duration connectTimeout,
                                  Duration readTimeout,
                                  ObjectMapper objectMapper,
                                  SentinelMetrics metrics) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(apiKey, "apiKey");
        this.apiKey = apiKey;
        this.evaluationPath = normalizePath(evaluationPath);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.metrics = metrics != null ? metrics : SentinelMetrics.NOOP;

        Duration connect = connectTimeout != null ? connectTimeout : Duration.ofMillis(500);
        Duration read = readTimeout != null ? readTimeout : Duration.ofSeconds(2);
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(connect)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(read);

        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.restClient = RestClient.builder()
            .baseUrl(root)
            .requestFactory(requestFactory)
            .build();
    }

    /**
     * POST EvaluationRequest once (no retry). On any transport/contract failure returns remote-failure response.
     */
    public EvaluationResponse evaluate(EvaluationRequest request) {
        Objects.requireNonNull(request, "request");
        metrics.recordRemoteEvaluationAttempt();
        long start = System.nanoTime();
        RemoteEvaluationOutcome outcome = RemoteEvaluationOutcome.UNEXPECTED;
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            String raw = restClient.post()
                .uri(evaluationPath)
                .contentType(MediaType.APPLICATION_JSON)
                .header(RemoteEvaluationConstants.API_KEY_HEADER, apiKey)
                .body(body)
                .retrieve()
                .body(String.class);

            if (raw == null || raw.isBlank()) {
                outcome = RemoteEvaluationOutcome.MALFORMED_RESPONSE;
                return fail(request.correlationId(), outcome);
            }
            EvaluationResponse response;
            try {
                response = objectMapper.readValue(raw, EvaluationResponse.class);
            } catch (Exception parseEx) {
                outcome = RemoteEvaluationOutcome.MALFORMED_RESPONSE;
                return fail(request.correlationId(), outcome);
            }
            try {
                EvaluationResponseValidator.validate(response, request.correlationId());
            } catch (EvaluationContractException vex) {
                String msg = vex.getMessage() != null ? vex.getMessage() : "";
                if (msg.contains("contractVersion")) {
                    outcome = RemoteEvaluationOutcome.VERSION_MISMATCH;
                } else if (msg.contains("correlationId")) {
                    outcome = RemoteEvaluationOutcome.CORRELATION_MISMATCH;
                } else {
                    outcome = RemoteEvaluationOutcome.MALFORMED_RESPONSE;
                }
                return fail(request.correlationId(), outcome);
            }
            outcome = RemoteEvaluationOutcome.SUCCESS;
            metrics.recordEvaluationStatuses(
                response.evaluationStatuses().stream()
                    .map(name -> {
                        try {
                            return dev.aisentinel.core.decision.EvaluationStatus.valueOf(name);
                        } catch (IllegalArgumentException ignored) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList());
            return response;
        } catch (RestClientResponseException httpEx) {
            int status = httpEx.getStatusCode().value();
            if (status == 401 || status == 403) {
                outcome = RemoteEvaluationOutcome.AUTH_REJECTED;
            } else {
                outcome = RemoteEvaluationOutcome.HTTP_ERROR;
            }
            return fail(request.correlationId(), outcome);
        } catch (RestClientException restEx) {
            String name = restEx.getClass().getSimpleName().toLowerCase();
            String msg = restEx.getMessage() != null ? restEx.getMessage().toLowerCase() : "";
            if (name.contains("timeout") || msg.contains("timed out") || msg.contains("timeout")) {
                outcome = RemoteEvaluationOutcome.TIMEOUT;
            } else {
                outcome = RemoteEvaluationOutcome.CONNECTION_FAILURE;
            }
            return fail(request.correlationId(), outcome);
        } catch (Exception ex) {
            String name = ex.getClass().getSimpleName().toLowerCase();
            if (name.contains("json") || name.contains("jackson")) {
                outcome = RemoteEvaluationOutcome.SERIALIZATION_FAILURE;
            } else {
                outcome = RemoteEvaluationOutcome.UNEXPECTED;
            }
            return fail(request.correlationId(), outcome);
        } finally {
            metrics.recordRemoteEvaluationLatencyNanos(System.nanoTime() - start);
            metrics.recordRemoteEvaluationOutcome(outcome.name());
        }
    }

    private EvaluationResponse fail(String correlationId, RemoteEvaluationOutcome outcome) {
        log.warn("Remote evaluation failed outcome={} correlationId={}", outcome, correlationId);
        metrics.recordFailOpen(FailOpenReason.REMOTE_EVALUATION_FAILURE);
        return EvaluationFailureResponses.remoteFailure(correlationId);
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/ai-sentinel/v1/evaluation";
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
