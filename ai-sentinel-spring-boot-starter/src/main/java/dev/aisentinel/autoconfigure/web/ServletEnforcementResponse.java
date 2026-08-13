package dev.aisentinel.autoconfigure.web;

import dev.aisentinel.core.enforcement.EnforcementResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Objects;

/**
 * Servlet adapter for {@link EnforcementResponse}. Writes go through the response writer.
 * When {@link HttpServletResponse#isCommitted()} is true, callers should skip mutation via
 * {@link #isCommitted()}; otherwise an already committed response may surface as {@link IOException}.
 */
public final class ServletEnforcementResponse implements EnforcementResponse {

    private final HttpServletResponse response;

    public ServletEnforcementResponse(HttpServletResponse response) {
        this.response = Objects.requireNonNull(response, "response");
    }

    /** The wrapped response, for integrations that still need servlet-specific data. */
    public HttpServletResponse getServletResponse() {
        return response;
    }

    @Override
    public void setStatus(int statusCode) {
        response.setStatus(statusCode);
    }

    @Override
    public void setContentType(String contentType) {
        response.setContentType(contentType);
    }

    @Override
    public void writeBody(String body) throws IOException {
        response.getWriter().write(body);
    }

    @Override
    public boolean isCommitted() {
        return response.isCommitted();
    }
}
