package dev.aisentinel.core.replay;

/**
 * Runtime exception with replay-specific failure classification.
 */
public final class ReplayException extends RuntimeException {

    private final ReplayFailureKind kind;

    public ReplayException(ReplayFailureKind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ReplayException(ReplayFailureKind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public ReplayFailureKind kind() {
        return kind;
    }
}
