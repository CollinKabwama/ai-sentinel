package dev.aisentinel.core.evaluation;

import java.nio.file.Path;

/**
 * Public CLI entry for Level-3 same-framework statistical vs Isolation Forest comparison.
 * <p>
 * Offline evaluation evidence only. Same-origin detectors; not an independent third-party
 * benchmark and not a detector superiority ranking.
 */
public final class SameFrameworkDetectorComparisonMain {

    private SameFrameworkDetectorComparisonMain() {
    }

    public static void main(String[] args) {
        Path repositoryRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        System.exit(SameFrameworkDetectorComparisonCli.run(args, System.out, System.err, repositoryRoot));
    }
}
