package clockwork.bosses.api;

/** Result at the return boundary; native AI/other mods still own later changes. */
public enum BossTargetRequest {
    ACCEPTED, UNCHANGED, INELIGIBLE, HELD, UNSUPPORTED,
    /** A native veto, redirection or synchronous encounter interruption. */
    REJECTED
}
