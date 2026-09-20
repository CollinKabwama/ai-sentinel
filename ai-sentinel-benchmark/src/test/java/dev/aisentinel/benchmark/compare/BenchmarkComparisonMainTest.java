package dev.aisentinel.benchmark.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkComparisonMainTest {

    @TempDir
    Path tempDir;

    @Test
    void passComparisonReturnsZero() throws Exception {
        int code = runWithFixture("jmh-pass-fixture.json", "manifest-reference-fixture.json");

        assertThat(code).isEqualTo(0);
    }

    @Test
    void regressionComparisonReturnsOne() throws Exception {
        int code = runWithFixture("jmh-regression-fixture.json", "manifest-reference-fixture.json");

        assertThat(code).isEqualTo(1);
    }

    @Test
    void environmentMismatchReturnsZeroAndReportsInformationalOnly() throws Exception {
        Path candidate = tempDir.resolve("jmh-env.json");
        Path manifest = tempDir.resolve("manifest-env.json");
        Path output = tempDir.resolve("report.json");
        Files.copy(fixture("jmh-regression-fixture.json"), candidate);
        Files.copy(fixture("manifest-mismatch-fixture.json"), manifest);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int code = BenchmarkComparisonMain.run(new String[] {
            "--family", "jmh",
            "--baseline", fixture("reference-baseline-fixture.json").toString(),
            "--candidate", candidate.toString(),
            "--output", output.toString()
        }, new PrintStream(stdout), System.err);

        assertThat(code).isEqualTo(0);
        assertThat(stdout.toString()).contains("Informational only: 2");
    }

    @Test
    void invalidInputReturnsToolErrorCode() throws Exception {
        int code = BenchmarkComparisonMain.run(new String[] {"--family", "jmh"}, System.out, System.err);

        assertThat(code).isEqualTo(2);
    }

    @Test
    void malformedInputReturnsToolErrorCode() throws Exception {
        Path malformed = tempDir.resolve("malformed.json");
        Path output = tempDir.resolve("report.json");
        Files.writeString(malformed, "{not-json");

        int code = BenchmarkComparisonMain.run(new String[] {
            "--family", "jmh",
            "--baseline", fixture("reference-baseline-fixture.json").toString(),
            "--candidate", malformed.toString(),
            "--output", output.toString()
        }, System.out, System.err);

        assertThat(code).isEqualTo(2);
    }

    @Test
    void deploymentAndResourceComparisonsAreSupported() throws Exception {
        Path outputA = tempDir.resolve("deployment-report.json");
        Path outputB = tempDir.resolve("resource-report.json");

        int deployment = BenchmarkComparisonMain.run(new String[] {
            "--family", "deployment",
            "--baseline", fixture("deployment-baseline-fixture.json").toString(),
            "--candidate", fixture("deployment-candidate-fixture.json").toString(),
            "--output", outputA.toString()
        }, System.out, System.err);
        int resources = BenchmarkComparisonMain.run(new String[] {
            "--family", "resources",
            "--baseline", fixture("resource-baseline-fixture.json").toString(),
            "--candidate", fixture("resource-candidate-fixture.json").toString(),
            "--output", outputB.toString()
        }, System.out, System.err);

        assertThat(deployment).isEqualTo(0);
        assertThat(resources).isEqualTo(0);
        assertThat(Files.readString(outputA)).contains("JAVA_REMOTE_NORMAL");
        assertThat(Files.readString(outputB)).contains("RESOURCE_IN_PROCESS");
    }

    private int runWithFixture(String jmhFixture, String manifestFixture) throws Exception {
        Path candidate = tempDir.resolve("jmh-case.json");
        Path manifest = tempDir.resolve("manifest-case.json");
        Path output = tempDir.resolve("report.json");
        Files.copy(fixture(jmhFixture), candidate);
        Files.copy(fixture(manifestFixture), manifest);
        return BenchmarkComparisonMain.run(new String[] {
            "--family", "jmh",
            "--baseline", fixture("reference-baseline-fixture.json").toString(),
            "--candidate", candidate.toString(),
            "--output", output.toString()
        }, System.out, System.err);
    }

    private Path fixture(String name) {
        return Path.of("src/test/resources/benchmark-comparison/" + name);
    }
}
