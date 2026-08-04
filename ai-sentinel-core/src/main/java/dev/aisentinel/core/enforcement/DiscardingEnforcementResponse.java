package dev.aisentinel.core.enforcement;

/**
 * {@link EnforcementResponse} that ignores all writes. Used in MONITOR mode so scoring and telemetry still run
 * while a custom {@link EnforcementHandler} cannot commit status or body to the real client response.
 */
public final class DiscardingEnforcementResponse implements EnforcementResponse {

    public static final DiscardingEnforcementResponse INSTANCE = new DiscardingEnforcementResponse();

    private DiscardingEnforcementResponse() {
    }

    @Override
    public void setStatus(int statusCode) {
        // intentionally discarded
    }

    @Override
    public void setContentType(String contentType) {
        // intentionally discarded
    }

    @Override
    public void writeBody(String body) {
        // intentionally discarded
    }
}
