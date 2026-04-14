package io.aisentinel.core.identity;

/**
 * Keys for storing identity-related values in {@link io.aisentinel.core.model.RequestContext}.
 */
public final class IdentityContextKeys {

    /** {@link io.aisentinel.core.identity.model.IdentityContext} when the identity module populates it. */
    public static final String IDENTITY_CONTEXT = "io.aisentinel.identity.context";

    private IdentityContextKeys() {}
}
