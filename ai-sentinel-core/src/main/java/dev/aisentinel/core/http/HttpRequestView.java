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
 * accessors for a mechanical migration; renaming to fully neutral terms is deferred API polish, not a
 * correctness requirement.
 * <p>
 * <strong>Thread safety:</strong> one view instance is bound to a single request; do not share across threads.
 */
public interface HttpRequestView {

    /** Request path (servlet {@code getRequestURI()} semantics); never {@code null} for a well-formed adapter. */
    String getRequestURI();

    /** HTTP method (for example {@code GET}, {@code POST}). */
    String getMethod();

    /**
     * Query/form parameters. Map should be unmodifiable; {@code String[]} values must not be mutated by callers.
     */
    Map<String, String[]> getParameterMap();

    /** First header value for {@code name}, or {@code null} if absent. Name matching is adapter-defined (often case-insensitive). */
    String getHeader(String name);

    /** Enumeration of header names present on the request. */
    Enumeration<String> getHeaderNames();

    /** Client address as exposed by the adapter (may be the immediate peer if proxies are not trusted). */
    String getRemoteAddr();

    /** Optional session id if a session exists; {@code null} otherwise. */
    String getSessionId();

    /** {@code true} when a session already exists for this request (adapters must not create one to answer). */
    boolean hasSession();

    /**
     * Whether the session was created by the current request (servlet {@code HttpSession#isNew()} semantics).
     * When {@link #hasSession()} is false, returns {@code false}.
     */
    boolean isNewSession();

    /** Epoch millis when the session was created, or {@code 0} when there is no session. */
    long getSessionCreationTimeMillis();

    /** Epoch millis of the last access to the session, or {@code 0} when there is no session. */
    long getSessionLastAccessedTimeMillis();
}
