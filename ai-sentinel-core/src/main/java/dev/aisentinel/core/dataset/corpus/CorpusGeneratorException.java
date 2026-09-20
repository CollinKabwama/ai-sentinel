package dev.aisentinel.core.dataset.corpus;

/**
 * Raised when a scenario cannot be compiled into a deterministic corpus.
 */
public final class CorpusGeneratorException extends RuntimeException {

    public CorpusGeneratorException(String message) {
        super(message);
    }

    public CorpusGeneratorException(String message, Throwable cause) {
        super(message, cause);
    }
}
