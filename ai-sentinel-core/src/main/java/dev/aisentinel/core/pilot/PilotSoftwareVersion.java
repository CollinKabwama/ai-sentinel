package dev.aisentinel.core.pilot;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Packaging software version for pilot provenance (same resource as Evaluation Kit).
 */
public final class PilotSoftwareVersion {

    private static final String RESOURCE =
        "dev/aisentinel/core/evaluation/kit-software.properties";

    private PilotSoftwareVersion() {
    }

    /**
     * Returns packaging software version, or {@code "unknown"} when unavailable
     * (never fabricates a git commit).
     */
    public static String resolve() {
        try (InputStream in = PilotSoftwareVersion.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("softwareVersion");
                if (version != null && !version.isBlank() && !version.contains("${")) {
                    return version.trim();
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        Package pkg = PilotSoftwareVersion.class.getPackage();
        if (pkg != null) {
            String implementation = pkg.getImplementationVersion();
            if (implementation != null && !implementation.isBlank()) {
                return implementation.trim();
            }
        }
        return "unknown";
    }
}
