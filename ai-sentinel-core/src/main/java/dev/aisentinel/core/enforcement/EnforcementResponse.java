package dev.aisentinel.core.enforcement;

import java.io.IOException;

/**
 * Minimal write side of an HTTP response used by {@link EnforcementHandler} implementations, decoupling the
 * decision core from any servlet or web framework.
 * <p>
 * Adapters live in the integration layer (for example {@code ServletEnforcementResponse} in the Spring Boot starter).
 */
public interface EnforcementResponse {

    void setStatus(int statusCode);

    void setContentType(String contentType);

    void writeBody(String body) throws IOException;
}
