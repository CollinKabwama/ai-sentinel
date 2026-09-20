package dev.aisentinel.benchmark;

/**
 * Version identifiers for the benchmark suite output format.
 * Increment {@link #SUITE_FORMAT_VERSION} when the metadata JSON schema changes incompatibly.
 */
public final class BenchmarkSuiteVersions {

    /** Machine-readable metadata document format. */
    public static final String SUITE_FORMAT_VERSION = "1";

    /** Human label for this foundation suite. */
    public static final String SUITE_NAME = "ai-sentinel-benchmark-foundation";

    private BenchmarkSuiteVersions() {
    }
}
