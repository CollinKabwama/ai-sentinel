package dev.aisentinel.benchmark;

import java.util.Objects;

/**
 * Host and build metadata recorded with every benchmark run.
 * Missing values use {@code null} rather than invented placeholders.
 */
public final class EnvironmentMetadata {

    private final String sentinelVersion;
    private final String gitCommit;
    private final String suiteFormatVersion;
    private final String suiteName;
    private final String capturedAtUtc;
    private final String javaVersion;
    private final String javaVendor;
    private final String javaVmName;
    private final String osName;
    private final String osVersion;
    private final String osArch;
    private final Integer availableProcessors;
    private final Long maxHeapBytes;
    private final Long totalMemoryBytes;
    private final String jvmInputArguments;
    private final String featureSchemaVersion;
    private final String deploymentMode;
    private final String stateBackend;

    private EnvironmentMetadata(Builder b) {
        this.sentinelVersion = b.sentinelVersion;
        this.gitCommit = b.gitCommit;
        this.suiteFormatVersion = b.suiteFormatVersion;
        this.suiteName = b.suiteName;
        this.capturedAtUtc = b.capturedAtUtc;
        this.javaVersion = b.javaVersion;
        this.javaVendor = b.javaVendor;
        this.javaVmName = b.javaVmName;
        this.osName = b.osName;
        this.osVersion = b.osVersion;
        this.osArch = b.osArch;
        this.availableProcessors = b.availableProcessors;
        this.maxHeapBytes = b.maxHeapBytes;
        this.totalMemoryBytes = b.totalMemoryBytes;
        this.jvmInputArguments = b.jvmInputArguments;
        this.featureSchemaVersion = b.featureSchemaVersion;
        this.deploymentMode = b.deploymentMode;
        this.stateBackend = b.stateBackend;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String sentinelVersion() { return sentinelVersion; }
    public String gitCommit() { return gitCommit; }
    public String suiteFormatVersion() { return suiteFormatVersion; }
    public String suiteName() { return suiteName; }
    public String capturedAtUtc() { return capturedAtUtc; }
    public String javaVersion() { return javaVersion; }
    public String javaVendor() { return javaVendor; }
    public String javaVmName() { return javaVmName; }
    public String osName() { return osName; }
    public String osVersion() { return osVersion; }
    public String osArch() { return osArch; }
    public Integer availableProcessors() { return availableProcessors; }
    public Long maxHeapBytes() { return maxHeapBytes; }
    public Long totalMemoryBytes() { return totalMemoryBytes; }
    public String jvmInputArguments() { return jvmInputArguments; }
    public String featureSchemaVersion() { return featureSchemaVersion; }
    public String deploymentMode() { return deploymentMode; }
    public String stateBackend() { return stateBackend; }

    public static final class Builder {
        private String sentinelVersion;
        private String gitCommit;
        private String suiteFormatVersion = BenchmarkSuiteVersions.SUITE_FORMAT_VERSION;
        private String suiteName = BenchmarkSuiteVersions.SUITE_NAME;
        private String capturedAtUtc;
        private String javaVersion;
        private String javaVendor;
        private String javaVmName;
        private String osName;
        private String osVersion;
        private String osArch;
        private Integer availableProcessors;
        private Long maxHeapBytes;
        private Long totalMemoryBytes;
        private String jvmInputArguments;
        private String featureSchemaVersion;
        private String deploymentMode = "in-process";
        private String stateBackend = "local-memory";

        public Builder sentinelVersion(String value) { this.sentinelVersion = value; return this; }
        public Builder gitCommit(String value) { this.gitCommit = value; return this; }
        public Builder suiteFormatVersion(String value) { this.suiteFormatVersion = value; return this; }
        public Builder suiteName(String value) { this.suiteName = value; return this; }
        public Builder capturedAtUtc(String value) { this.capturedAtUtc = value; return this; }
        public Builder javaVersion(String value) { this.javaVersion = value; return this; }
        public Builder javaVendor(String value) { this.javaVendor = value; return this; }
        public Builder javaVmName(String value) { this.javaVmName = value; return this; }
        public Builder osName(String value) { this.osName = value; return this; }
        public Builder osVersion(String value) { this.osVersion = value; return this; }
        public Builder osArch(String value) { this.osArch = value; return this; }
        public Builder availableProcessors(Integer value) { this.availableProcessors = value; return this; }
        public Builder maxHeapBytes(Long value) { this.maxHeapBytes = value; return this; }
        public Builder totalMemoryBytes(Long value) { this.totalMemoryBytes = value; return this; }
        public Builder jvmInputArguments(String value) { this.jvmInputArguments = value; return this; }
        public Builder featureSchemaVersion(String value) { this.featureSchemaVersion = value; return this; }
        public Builder deploymentMode(String value) { this.deploymentMode = value; return this; }
        public Builder stateBackend(String value) { this.stateBackend = value; return this; }

        public EnvironmentMetadata build() {
            Objects.requireNonNull(suiteFormatVersion, "suiteFormatVersion");
            Objects.requireNonNull(suiteName, "suiteName");
            return new EnvironmentMetadata(this);
        }
    }
}
