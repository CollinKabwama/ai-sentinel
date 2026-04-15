package io.aisentinel.core.fusion;

/**
 * Keys for fusion outputs on {@link io.aisentinel.core.model.RequestContext}.
 */
public final class FusionContextKeys {

    /** Latest {@link FusedRisk} for this request when fusion ran; absent when fusion is off or skipped. */
    public static final String FUSED_RISK = "io.aisentinel.fusion.FUSED_RISK";

    private FusionContextKeys() {}
}
