package dev.aisentinel.core.enforcement;

import java.io.IOException;

/**
 * Minimal write side of an HTTP response used by {@link EnforcementHandler} implementations, decoupling the
 * decision core from any servlet or web framework.
 * <p>
 * Adapters live in the integration layer (for example {@code ServletEnforcementResponse} in the Spring Boot starter).
 * Callers should treat writes as best-effort: an already committed response may surface as {@link IOException},
 * and handlers should consult {@link #isCommitted()} before mutating status or body when possible.
 * <p>
 * <strong>Thread safety:</strong> one response instance is bound to a single request; do not share across threads.
 */
public interface EnforcementResponse {

    /** Sets the HTTP status code that should be returned to the client. */
    void setStatus(int statusCode);

    /** Sets the response content type (for example {@code text/plain;charset=UTF-8}). */
    void setContentType(String contentType);

    /** Writes a response body. May throw if the underlying response is already committed. */
    void writeBody(String body) throws IOException;

    /**
     * Whether the underlying HTTP response is already committed (headers/body flushed).
     * Default {@code false} for non-servlet or test adapters; servlet adapters override.
     * When {@code true}, status/body mutations must be skipped — enforcement state and telemetry
     * may still be recorded.
     */
    default boolean isCommitted() {
        return false;
    }
}
