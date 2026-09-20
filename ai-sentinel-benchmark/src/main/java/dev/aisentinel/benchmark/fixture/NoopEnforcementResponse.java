package dev.aisentinel.benchmark.fixture;

import dev.aisentinel.core.enforcement.EnforcementResponse;

import java.io.IOException;

/** No-op response sink for pipeline benchmarks. */
public final class NoopEnforcementResponse implements EnforcementResponse {

    public static final NoopEnforcementResponse INSTANCE = new NoopEnforcementResponse();

    private NoopEnforcementResponse() {
    }

    @Override
    public void setStatus(int statusCode) {
    }

    @Override
    public void setContentType(String contentType) {
    }

    @Override
    public void writeBody(String body) throws IOException {
    }
}
