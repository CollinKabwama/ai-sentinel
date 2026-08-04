package dev.aisentinel.core.http;

import java.util.Enumeration;
import java.util.Map;

/**
 * Read-only view of an inbound HTTP request, decoupling the decision core from any servlet or web framework.
 * <p>
 * Adapters live in the integration layer (for example {@code ServletHttpRequestView} in the Spring Boot starter).
 * Implementations are read-only with respect to the underlying request and must never consume the request body.
 * <p>
 * {@link #getParameterMap()} values are {@code String[]} for servlet parity. Implementations should expose an
 * unmodifiable map; callers must not mutate array elements. Method names intentionally mirror common servlet
 * accessors for a mechanical migration; renaming to fully neutral terms is a future enhancement, not a
 * correctness requirement.
 */
public interface HttpRequestView {

    String getRequestURI();

    String getMethod();

    Map<String, String[]> getParameterMap();

    String getHeader(String name);

    Enumeration<String> getHeaderNames();

    String getRemoteAddr();

    /** Optional session id if a session exists; null otherwise. */
    String getSessionId();

    boolean hasSession();

    /**
     * Whether the session was created by the current request (servlet {@code HttpSession#isNew()} semantics).
     * When {@link #hasSession()} is false, returns {@code false}.
     */
    boolean isNewSession();

    /** Epoch millis when the session was created, or {@code 0} when there is no session. */
    long getSessionCreationTimeMillis();

    /** Epoch millis of the previous access to the session, or {@code 0} when there is no session. */
    long getSessionLastAccessedTimeMillis();
}
