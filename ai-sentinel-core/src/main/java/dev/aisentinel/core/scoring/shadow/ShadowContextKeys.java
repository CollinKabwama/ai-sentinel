package dev.aisentinel.core.scoring.shadow;

/**
 * {@link RequestContext} key for the optional per-request shadow observation.
 * <p>
 * Presence of an observation never alters {@code RiskDecision} authority fields.
 */
public final class ShadowContextKeys {

    public static final String SHADOW_OBSERVATION = "aisentinel.shadow.observation";

    private ShadowContextKeys() {
    }
}
