package dev.aisentinel.core.identity;

/**
 * Keys for storing identity-related values in {@link dev.aisentinel.core.model.RequestContext}.
 */
public final class IdentityContextKeys {

    /** {@link dev.aisentinel.core.identity.model.IdentityContext} when the identity module populates it. */
    public static final String IDENTITY_CONTEXT = "dev.aisentinel.identity.context";

    private IdentityContextKeys() {}
}
