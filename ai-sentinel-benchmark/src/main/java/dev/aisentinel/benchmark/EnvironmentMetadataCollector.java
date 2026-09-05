package dev.aisentinel.benchmark;

import dev.aisentinel.core.model.FeatureSchema;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Collects host/build metadata for benchmark manifests.
 * Unresolvable fields remain {@code null}.
 */
public final class EnvironmentMetadataCollector {

    private EnvironmentMetadataCollector() {
    }

    public static EnvironmentMetadata collect() {
        Runtime rt = Runtime.getRuntime();
        List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        return EnvironmentMetadata.builder()
            .sentinelVersion(readSentinelVersion())
            .gitCommit(readGitCommit())
            .capturedAtUtc(Instant.now().toString())
            .javaVersion(System.getProperty("java.version"))
            .javaVendor(System.getProperty("java.vendor"))
            .javaVmName(System.getProperty("java.vm.name"))
            .osName(System.getProperty("os.name"))
            .osVersion(System.getProperty("os.version"))
            .osArch(System.getProperty("os.arch"))
            .availableProcessors(rt.availableProcessors())
            .maxHeapBytes(rt.maxMemory())
            .totalMemoryBytes(rt.totalMemory())
            .jvmInputArguments(inputArgs.isEmpty() ? null : String.join(" ", inputArgs))
            .featureSchemaVersion(Integer.toString(FeatureSchema.VERSION))
            .deploymentMode("in-process")
            .stateBackend("local-memory")
            .build();
    }

    static String readSentinelVersion() {
        String fromProp = System.getProperty("aisentinel.benchmark.sentinelVersion");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        Package pkg = EnvironmentMetadataCollector.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null && !pkg.getImplementationVersion().isBlank()) {
            return pkg.getImplementationVersion();
        }
        String fromPom = readPomPropertiesVersion("dev.aisentinel", "ai-sentinel-benchmark");
        if (fromPom != null) {
            return fromPom;
        }
        return readPomPropertiesVersion("dev.aisentinel", "ai-sentinel-core");
    }

    private static String readPomPropertiesVersion(String groupId, String artifactId) {
        String path = "/META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties";
        try (InputStream in = EnvironmentMetadataCollector.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version");
            return version == null || version.isBlank() ? null : version.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    static String readGitCommit() {
        String fromProp = System.getProperty("aisentinel.benchmark.gitCommit");
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.lines().collect(Collectors.joining()).trim();
                return line.isEmpty() ? null : line;
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
