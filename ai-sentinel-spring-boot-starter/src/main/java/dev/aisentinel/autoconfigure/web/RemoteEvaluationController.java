package dev.aisentinel.autoconfigure.web;

import dev.aisentinel.autoconfigure.config.SentinelProperties;
import dev.aisentinel.autoconfigure.evaluation.ApiKeyAuthenticator;
import dev.aisentinel.autoconfigure.evaluation.RemoteEvaluationConstants;
import dev.aisentinel.core.contract.EvaluationContractException;
import dev.aisentinel.core.contract.EvaluationExecutor;
import dev.aisentinel.core.contract.EvaluationRequest;
import dev.aisentinel.core.contract.EvaluationResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

/**
 * Authenticated evaluation-only endpoint ({@code POST /ai-sentinel/v1/evaluation}).
 * Does not expose admin/quarantine/baseline APIs.
 * <p>
 * Authenticated callers are trusted adapters asserting contract fields; enforcement mode remains
 * server configuration and cannot be overridden by request attributes.
 * <p>
 * Credential ingress is exclusively {@link RemoteEvaluationConstants#API_KEY_HEADER}. Duplicate
 * header values are rejected (no silent first-value selection). Auth failure responses never echo
 * credential material.
 */
@RestController
public class RemoteEvaluationController {

    private static final Logger log = LoggerFactory.getLogger(RemoteEvaluationController.class);

    /** Stable v1 path (also default client path / filter exclude). */
    public static final String PATH = "/ai-sentinel/v1/evaluation";

    private final EvaluationExecutor localExecutor;
    private final SentinelProperties properties;

    public RemoteEvaluationController(EvaluationExecutor localEvaluationExecutorForServer,
                                      SentinelProperties properties) {
        this.localExecutor = localEvaluationExecutorForServer;
        this.properties = properties;
    }

    @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> evaluate(@RequestBody(required = false) EvaluationRequest request,
                                      HttpServletRequest httpRequest) {
        SentinelProperties.Evaluation.Server server = properties.getEvaluation().getServer();
        ApiKeyHeader.Resolution credential = ApiKeyHeader.resolve(httpRequest);
        if (credential.status() == ApiKeyHeader.Status.MISSING) {
            log.warn("Remote evaluation auth rejected: missing credential");
            return authFailure("missing_credential");
        }
        if (credential.status() == ApiKeyHeader.Status.AMBIGUOUS) {
            log.warn("Remote evaluation auth rejected: ambiguous credential");
            return authFailure("ambiguous_credential");
        }
        if (!ApiKeyAuthenticator.matches(server.getApiKey(), credential.value())) {
            log.warn("Remote evaluation auth rejected: credential rejected");
            return authFailure("auth_rejected");
        }

        int maxBytes = server.getMaxRequestBytes();
        int contentLength = httpRequest.getContentLength();
        if (contentLength > maxBytes) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
        }

        if (request == null) {
            return ResponseEntity.badRequest().body("{\"error\":\"missing_body\"}");
        }

        try {
            EvaluationResponse response = localExecutor.evaluate(request);
            return ResponseEntity.ok(response);
        } catch (EvaluationContractException ex) {
            log.warn("Remote evaluation contract validation failed: {}", ex.getMessage());
            return ResponseEntity.badRequest().body("{\"error\":\"contract_validation\"}");
        } catch (RuntimeException ex) {
            log.warn("Remote evaluation failed: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private static ResponseEntity<String> authFailure(String code) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"" + code + "\"}");
    }

    /**
     * Resolves the remote-evaluation API-key header without silently picking among
     * duplicate values. Ambiguous multi-value credentials are rejected.
     */
    public static final class ApiKeyHeader {

        public enum Status {
            /** Header absent or blank/whitespace-only. */
            MISSING,
            /** More than one header value was supplied. */
            AMBIGUOUS,
            /** Exactly one non-blank value. */
            PRESENT
        }

        public record Resolution(Status status, String value) {
            public Resolution {
                status = Objects.requireNonNull(status, "status");
                if (status == Status.PRESENT) {
                    Objects.requireNonNull(value, "value");
                } else {
                    value = null;
                }
            }
        }

        private ApiKeyHeader() {
        }

        public static Resolution resolve(HttpServletRequest request) {
            Objects.requireNonNull(request, "request");
            Enumeration<String> values = request.getHeaders(RemoteEvaluationConstants.API_KEY_HEADER);
            if (values == null || !values.hasMoreElements()) {
                return new Resolution(Status.MISSING, null);
            }
            List<String> collected = new ArrayList<>(2);
            while (values.hasMoreElements()) {
                collected.add(values.nextElement());
                if (collected.size() > 1) {
                    return new Resolution(Status.AMBIGUOUS, null);
                }
            }
            String single = collected.get(0);
            if (single == null || single.isBlank()) {
                return new Resolution(Status.MISSING, null);
            }
            return new Resolution(Status.PRESENT, single);
        }
    }
}
