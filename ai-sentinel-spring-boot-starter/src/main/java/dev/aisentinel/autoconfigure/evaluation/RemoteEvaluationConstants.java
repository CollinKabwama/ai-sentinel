package dev.aisentinel.autoconfigure.evaluation;

/**
 * Shared constants for remote evaluation auth and headers.
 * <p>
 * Authenticated remote clients are <strong>trusted adapters</strong>: after API-key verification,
 * fields such as {@code identityKey}, {@code remoteAddress}, and {@code trustSignals} are accepted
 * as adapter-asserted inputs. Authentication does not cryptographically prove end-user identity.
 */
public final class RemoteEvaluationConstants {

    /** Header carrying the shared evaluation service credential (not end-user Authorization). */
    public static final String API_KEY_HEADER = "X-AI-Sentinel-Api-Key";

    private RemoteEvaluationConstants() {
    }
}
