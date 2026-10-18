package dev.aisentinel.core.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Resolves packaging software version for Evaluation Kit evidence.
 * <p>
 * This is distinct from {@code aiSentinelVersion} (reference evaluation configuration)
 * and from {@code aiSentinelBuildId} (actual build/commit identity when available).
 * When software version cannot be resolved truthfully, the field is omitted.
 */
final class KitSoftwareIdentity {

    private static final String RESOURCE =
        "dev/aisentinel/core/evaluation/kit-software.properties";

    private KitSoftwareIdentity() {
    }

    /**
     * Packaging software version (for example Maven project version), when known.
     * Never invents a build/commit SHA.
     */
    static Optional<String> softwareVersion() {
        Optional<String> fromResource = readResourceVersion();
        if (fromResource.isPresent()) {
            return fromResource;
        }
        Package pkg = KitSoftwareIdentity.class.getPackage();
        if (pkg != null) {
            String implementation = pkg.getImplementationVersion();
            if (implementation != null && !implementation.isBlank()) {
                return Optional.of(implementation.trim());
            }
        }
        return Optional.empty();
    }

    /**
     * Actual build/commit identity. Currently unavailable deterministically for all
     * Kit execution modes; callers must omit {@code aiSentinelBuildId} rather than fabricate.
     */
    static Optional<String> buildId() {
        return Optional.empty();
    }

    private static Optional<String> readResourceVersion() {
        try (InputStream in = KitSoftwareIdentity.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Optional.empty();
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("softwareVersion");
            if (version == null || version.isBlank() || version.contains("${")) {
                return Optional.empty();
            }
            return Optional.of(version.trim());
        } catch (IOException ex) {
            return Optional.empty();
        }
    }
}
