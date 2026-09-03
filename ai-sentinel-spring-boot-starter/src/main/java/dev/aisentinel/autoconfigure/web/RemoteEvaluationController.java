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

/**
 * Authenticated evaluation-only endpoint ({@code POST /ai-sentinel/v1/evaluation}).
 * Does not expose admin/quarantine/baseline APIs.
 * <p>
 * Authenticated callers are trusted adapters asserting contract fields; enforcement mode remains
 * server configuration and cannot be overridden by request attributes.
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
        String provided = httpRequest.getHeader(RemoteEvaluationConstants.API_KEY_HEADER);
        if (!ApiKeyAuthenticator.matches(server.getApiKey(), provided)) {
            log.warn("Remote evaluation auth rejected");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
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
}
