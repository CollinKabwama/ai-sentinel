package dev.aisentinel.core.http;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Mutable in-memory {@link HttpRequestView} for tests: no servlet container, no mocking framework.
 * Header lookups are case-insensitive, matching servlet semantics.
 */
public final class MapHttpRequestView implements HttpRequestView {

    private String requestUri = "/";
    private String method = "GET";
    private String remoteAddr = "127.0.0.1";
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String[]> parameters = new LinkedHashMap<>();
    private boolean sessionPresent;
    private String sessionId;
    private boolean newSession;
    private long sessionCreationTimeMillis;
    private long sessionLastAccessedTimeMillis;

    public MapHttpRequestView requestUri(String value) {
        this.requestUri = value;
        return this;
    }

    public MapHttpRequestView method(String value) {
        this.method = value;
        return this;
    }

    public MapHttpRequestView remoteAddr(String value) {
        this.remoteAddr = value;
        return this;
    }

    public MapHttpRequestView header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public MapHttpRequestView parameter(String name, String... values) {
        parameters.put(name, values);
        return this;
    }

    /**
     * Marks a session as present. When timestamps are equal, {@link #isNewSession()} defaults to {@code true}
     * (servlet {@code isNew()} for a session created by the current request).
     */
    public MapHttpRequestView session(String id, long creationTimeMillis, long lastAccessedTimeMillis) {
        return session(id, creationTimeMillis, lastAccessedTimeMillis,
            creationTimeMillis == lastAccessedTimeMillis);
    }

    public MapHttpRequestView session(String id, long creationTimeMillis, long lastAccessedTimeMillis,
                                      boolean newSession) {
        this.sessionPresent = true;
        this.sessionId = id;
        this.sessionCreationTimeMillis = creationTimeMillis;
        this.sessionLastAccessedTimeMillis = lastAccessedTimeMillis;
        this.newSession = newSession;
        return this;
    }

    public MapHttpRequestView noSession() {
        this.sessionPresent = false;
        this.sessionId = null;
        this.newSession = false;
        this.sessionCreationTimeMillis = 0L;
        this.sessionLastAccessedTimeMillis = 0L;
        return this;
    }

    @Override
    public String getRequestURI() {
        return requestUri;
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return Collections.unmodifiableMap(parameters);
    }

    @Override
    public String getHeader(String name) {
        return headers.get(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(headers.keySet());
    }

    @Override
    public String getRemoteAddr() {
        return remoteAddr;
    }

    @Override
    public String getSessionId() {
        return sessionPresent ? sessionId : null;
    }

    @Override
    public boolean hasSession() {
        return sessionPresent;
    }

    @Override
    public boolean isNewSession() {
        return sessionPresent && newSession;
    }

    @Override
    public long getSessionCreationTimeMillis() {
        return sessionCreationTimeMillis;
    }

    @Override
    public long getSessionLastAccessedTimeMillis() {
        return sessionLastAccessedTimeMillis;
    }
}
