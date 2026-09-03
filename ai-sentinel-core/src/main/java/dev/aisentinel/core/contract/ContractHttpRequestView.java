package dev.aisentinel.core.contract;

import dev.aisentinel.core.http.HttpRequestView;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Vector;

/**
 * Read-only {@link HttpRequestView} backed by an {@link EvaluationRequest}.
 * Enables existing extractors/engine to evaluate contract requests without servlet types.
 */
public final class ContractHttpRequestView implements HttpRequestView {

    private final EvaluationRequest request;
    private final Map<String, String[]> parameterMap;
    private final Map<String, String> headerLookup;

    public ContractHttpRequestView(EvaluationRequest request) {
        this.request = Objects.requireNonNull(request, "request");
        LinkedHashMap<String, String[]> params = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : request.parameters().entrySet()) {
            params.put(e.getKey(), new String[]{e.getValue()});
        }
        this.parameterMap = Collections.unmodifiableMap(params);
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : request.headers().entrySet()) {
            headers.put(e.getKey(), e.getValue());
        }
        this.headerLookup = Collections.unmodifiableMap(headers);
    }

    @Override
    public String getRequestURI() {
        return request.path();
    }

    @Override
    public String getMethod() {
        return request.method();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        LinkedHashMap<String, String[]> copy = new LinkedHashMap<>(parameterMap.size());
        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String getHeader(String name) {
        if (name == null) {
            return null;
        }
        return headerLookup.get(EvaluationRequest.normalizeHeaderName(name));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return new Vector<>(headerLookup.keySet()).elements();
    }

    @Override
    public String getRemoteAddr() {
        return request.remoteAddress() != null ? request.remoteAddress() : "";
    }

    @Override
    public String getSessionId() {
        return request.sessionPresent() ? request.sessionId() : null;
    }

    @Override
    public boolean hasSession() {
        return request.sessionPresent();
    }

    @Override
    public boolean isNewSession() {
        return request.sessionPresent() && request.sessionNew();
    }

    @Override
    public long getSessionCreationTimeMillis() {
        return request.sessionPresent() ? request.timestampEpochMillis() : 0L;
    }

    @Override
    public long getSessionLastAccessedTimeMillis() {
        return request.sessionPresent() ? request.timestampEpochMillis() : 0L;
    }
}
