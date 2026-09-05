package dev.aisentinel.benchmark.fixture;

import dev.aisentinel.core.http.HttpRequestView;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.Vector;

/**
 * Mutable in-memory {@link HttpRequestView} for benchmarks (no servlet container).
 */
public final class BenchmarkHttpRequestView implements HttpRequestView {

    private String requestUri = "/api/benchmark";
    private String method = "GET";
    private String remoteAddr = "203.0.113.10";
    private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String[]> parameters = new LinkedHashMap<>();

    public BenchmarkHttpRequestView requestUri(String value) {
        this.requestUri = value;
        return this;
    }

    public BenchmarkHttpRequestView method(String value) {
        this.method = value;
        return this;
    }

    public BenchmarkHttpRequestView remoteAddr(String value) {
        this.remoteAddr = value;
        return this;
    }

    public BenchmarkHttpRequestView header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public BenchmarkHttpRequestView parameter(String name, String... values) {
        parameters.put(name, values);
        return this;
    }

    public static BenchmarkHttpRequestView typical() {
        return new BenchmarkHttpRequestView()
            .requestUri("/api/benchmark")
            .method("GET")
            .remoteAddr("203.0.113.10")
            .header("User-Agent", "ai-sentinel-benchmark/1")
            .header("Accept", "application/json")
            .parameter("q", "1");
    }

    public static BenchmarkHttpRequestView small() {
        return new BenchmarkHttpRequestView()
            .requestUri("/api/ping")
            .method("GET")
            .remoteAddr("203.0.113.11");
    }

    public static BenchmarkHttpRequestView largerValid() {
        BenchmarkHttpRequestView view = new BenchmarkHttpRequestView()
            .requestUri("/api/search")
            .method("GET")
            .remoteAddr("203.0.113.12")
            .header("User-Agent", "ai-sentinel-benchmark/1")
            .header("Accept", "application/json")
            .header("X-Request-Id", "bench-larger-1")
            .header("Authorization", "Bearer not-a-real-token")
            .header("X-Token-Issued-At", Long.toString(System.currentTimeMillis() / 1000L - 120))
            .header("Content-Length", "32768");
        for (int i = 0; i < 16; i++) {
            view.parameter("p" + i, "v" + i);
        }
        return view;
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
        return new Vector<>(headers.keySet()).elements();
    }

    @Override
    public String getRemoteAddr() {
        return remoteAddr;
    }

    @Override
    public String getSessionId() {
        return null;
    }

    @Override
    public boolean hasSession() {
        return false;
    }

    @Override
    public boolean isNewSession() {
        return false;
    }

    @Override
    public long getSessionCreationTimeMillis() {
        return 0L;
    }

    @Override
    public long getSessionLastAccessedTimeMillis() {
        return 0L;
    }
}
