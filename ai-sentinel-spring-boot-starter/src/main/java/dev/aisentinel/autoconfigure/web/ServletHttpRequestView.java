package dev.aisentinel.autoconfigure.web;

import dev.aisentinel.core.http.HttpRequestView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;

/**
 * Servlet adapter for {@link HttpRequestView}. Sessions are read with {@code getSession(false)} so the view never
 * creates one; the underlying request is never mutated and the body is never consumed.
 */
public final class ServletHttpRequestView implements HttpRequestView {

    private final HttpServletRequest request;

    public ServletHttpRequestView(HttpServletRequest request) {
        this.request = Objects.requireNonNull(request, "request");
    }

    /** The wrapped request, for integrations that still need servlet-specific data. */
    public HttpServletRequest getServletRequest() {
        return request;
    }

    @Override
    public String getRequestURI() {
        return request.getRequestURI();
    }

    @Override
    public String getMethod() {
        return request.getMethod();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return request.getParameterMap();
    }

    @Override
    public String getHeader(String name) {
        return request.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return request.getHeaderNames();
    }

    @Override
    public String getRemoteAddr() {
        return request.getRemoteAddr();
    }

    @Override
    public String getSessionId() {
        HttpSession session = request.getSession(false);
        return session != null ? session.getId() : null;
    }

    @Override
    public boolean hasSession() {
        return request.getSession(false) != null;
    }

    @Override
    public boolean isNewSession() {
        HttpSession session = request.getSession(false);
        return session != null && session.isNew();
    }

    @Override
    public long getSessionCreationTimeMillis() {
        HttpSession session = request.getSession(false);
        return session != null ? session.getCreationTime() : 0L;
    }

    @Override
    public long getSessionLastAccessedTimeMillis() {
        HttpSession session = request.getSession(false);
        return session != null ? session.getLastAccessedTime() : 0L;
    }
}
