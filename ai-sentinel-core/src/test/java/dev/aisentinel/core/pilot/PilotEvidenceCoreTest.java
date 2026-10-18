package dev.aisentinel.core.pilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PilotEvidenceCoreTest {

    @Test
    void sameIdentityAndSecret_samePseudonym() {
        PilotPseudonymizer a = new PilotPseudonymizer("pilot-secret-16b!!");
        PilotPseudonymizer b = new PilotPseudonymizer("pilot-secret-16b!!");
        assertThat(a.pseudonymize("pipeline-id-1")).isEqualTo(b.pseudonymize("pipeline-id-1"));
    }

    @Test
    void sameIdentityDifferentSecret_differentPseudonym() {
        String id = "pipeline-id-1";
        String p1 = new PilotPseudonymizer("pilot-secret-aaaaaaa").pseudonymize(id);
        String p2 = new PilotPseudonymizer("pilot-secret-bbbbbbb").pseudonymize(id);
        assertThat(p1).isNotEqualTo(p2);
    }

    @Test
    void differentIdentitySameSecret_differentPseudonym() {
        PilotPseudonymizer p = new PilotPseudonymizer("pilot-secret-16b!!");
        assertThat(p.pseudonymize("id-a")).isNotEqualTo(p.pseudonymize("id-b"));
    }

    @Test
    void endpointPseudonymizationDoesNotExposeRawEndpoint() {
        PilotPseudonymizer p = new PilotPseudonymizer("pilot-secret-16b!!");
        String raw = "/reset/SECRET_TOKEN_VALUE";
        String endpoint = p.pseudonymizeEndpoint(raw);

        assertThat(endpoint).hasSize(64);
        assertThat(endpoint).doesNotContain("SECRET_TOKEN_VALUE");
        assertThat(endpoint).isEqualTo(p.pseudonymizeEndpoint(raw));
        assertThat(endpoint).isNotEqualTo(p.pseudonymize(raw));
    }

    @Test
    void rejectsShortOrEmptySecret() {
        assertThatThrownBy(() -> new PilotPseudonymizer(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PilotPseudonymizer("short"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameConfig_sameDigest() {
        PilotConfigSnapshot a = snapshot("MONITOR", "ALLOW_OR_MONITOR", "composite", "0.4.0");
        PilotConfigSnapshot b = snapshot("MONITOR", "ALLOW_OR_MONITOR", "composite", "0.4.0");
        assertThat(PilotConfigDigest.sha256Hex(a)).isEqualTo(PilotConfigDigest.sha256Hex(b));
    }

    @Test
    void differentMode_differentDigest() {
        PilotConfigSnapshot monitor = snapshot("MONITOR", "ALLOW_OR_MONITOR", "composite", "0.4.0");
        PilotConfigSnapshot enforce = snapshot("ENFORCE", "ALLOW_OR_MONITOR", "composite", "0.4.0");
        assertThat(PilotConfigDigest.sha256Hex(monitor)).isNotEqualTo(PilotConfigDigest.sha256Hex(enforce));
    }

    @Test
    void differentScorer_differentDigest() {
        PilotConfigSnapshot a = snapshot("MONITOR", "ALLOW_OR_MONITOR", "composite", "0.4.0");
        PilotConfigSnapshot b = snapshot("MONITOR", "ALLOW_OR_MONITOR", "if-only", "0.4.0");
        assertThat(PilotConfigDigest.sha256Hex(a)).isNotEqualTo(PilotConfigDigest.sha256Hex(b));
    }

    @Test
    void differentBaselinePolicy_differentDigest() {
        PilotConfigSnapshot a = snapshot("MONITOR", "ALLOW_OR_MONITOR", "composite", "0.4.0");
        PilotConfigSnapshot b = snapshot("MONITOR", "ALWAYS", "composite", "0.4.0");
        assertThat(PilotConfigDigest.sha256Hex(a)).isNotEqualTo(PilotConfigDigest.sha256Hex(b));
    }

    @Test
    void differentPolicyThreshold_differentDigest() {
        PilotConfigSnapshot a = snapshot("MONITOR", "ALLOW_OR_MONITOR", "composite", "0.4.0");
        PilotConfigSnapshot b = new PilotConfigSnapshot(
            "MONITOR", "ALLOW_OR_MONITOR", "composite", "1", "0.4.0", "1", "1",
            PilotObservation.EVIDENCE_CLASS, "threshold-policy", 0.25, 0.4, 0.6, 0.8);
        assertThat(PilotConfigDigest.sha256Hex(a)).isNotEqualTo(PilotConfigDigest.sha256Hex(b));
    }

    @TempDir
    Path temp;

    @Test
    void refusesNonEmptyOutputDirectory() throws Exception {
        Path dir = temp.resolve("prior");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("old.txt"), "x");
        PilotConfigSnapshot config = monitorConfig();
        PilotPseudonymizer pseudo = new PilotPseudonymizer("pilot-secret-16b!!");
        assertThatThrownBy(() -> new LocalPilotEvidenceSession(dir, "s1", config, pseudo))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("refusing overwrite");
    }

    @Test
    void rejectsUnsafePilotSessionIds() {
        PilotConfigSnapshot config = monitorConfig();
        PilotPseudonymizer pseudo = new PilotPseudonymizer("pilot-secret-16b!!");

        assertThatThrownBy(() -> new LocalPilotEvidenceSession(temp.resolve("newline"), "bad\nid", config, pseudo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pilotSessionId");
        assertThatThrownBy(() -> new LocalPilotEvidenceSession(temp.resolve("slash"), "../bad", config, pseudo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pilotSessionId");
        assertThatThrownBy(() -> new LocalPilotEvidenceSession(temp.resolve("long"), "a".repeat(81), config, pseudo))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pilotSessionId");
    }

    @Test
    void refusesSymbolicLinkOutputDirectory() throws Exception {
        Path target = temp.resolve("target");
        Path link = temp.resolve("link");
        Files.createDirectories(target);
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }

        PilotConfigSnapshot config = monitorConfig();
        PilotPseudonymizer pseudo = new PilotPseudonymizer("pilot-secret-16b!!");
        assertThatThrownBy(() -> new LocalPilotEvidenceSession(link, "symlink-session", config, pseudo))
            .isInstanceOf(java.io.IOException.class)
            .hasMessageContaining("symbolic link");
    }

    @Test
    void finalizeWritesExactlyThreeArtifactsAndDigestsMatch() throws Exception {
        Path dir = temp.resolve("session");
        PilotConfigSnapshot config = monitorConfig();
        try (LocalPilotEvidenceSession session = new LocalPilotEvidenceSession(
            dir, "pilot-session-1", config, new PilotPseudonymizer("pilot-secret-16b!!"))) {
            session.record(
                "identity-hash-aaa",
                decision("ALLOW", 0.05),
                1_000L,
                true
            );
            session.record(
                "identity-hash-bbb",
                decision("BLOCK", 0.7),
                2_000L,
                true
            );
        }

        assertThat(Files.list(dir).map(p -> p.getFileName().toString()).toList())
            .containsExactlyInAnyOrder(
                LocalPilotEvidenceSession.MANIFEST_FILE,
                LocalPilotEvidenceSession.OBSERVATIONS_FILE,
                LocalPilotEvidenceSession.SUMMARY_FILE
            );

        String observations = Files.readString(dir.resolve(LocalPilotEvidenceSession.OBSERVATIONS_FILE));
        String summary = Files.readString(dir.resolve(LocalPilotEvidenceSession.SUMMARY_FILE));
        String manifest = Files.readString(dir.resolve(LocalPilotEvidenceSession.MANIFEST_FILE));

        assertThat(observations).doesNotContain("pilot-secret");
        assertThat(summary).doesNotContain("pilot-secret");
        assertThat(manifest).doesNotContain("pilot-secret");
        assertThat(manifest).contains("\"runtimeMode\":\"MONITOR\"");
        assertThat(manifest).contains("\"observationCount\":2");
        assertThat(manifest).contains("\"pilotSessionId\":\"pilot-session-1\"");
        assertThat(observations).contains("\"enforcementApplied\":false");
        assertThat(observations).contains("\"requestOutcome\":\"CONTINUED\"");
        assertThat(observations).contains("\"riskDerivedAction\":\"BLOCK\"");

        String obsSha = sha256(Files.readAllBytes(dir.resolve(LocalPilotEvidenceSession.OBSERVATIONS_FILE)));
        String sumSha = sha256(summary.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(manifest).contains("\"observationsSha256\":\"" + obsSha + "\"");
        assertThat(manifest).contains("\"summarySha256\":\"" + sumSha + "\"");
    }

    @Test
    void fixedObservationsProduceDeterministicSummary() throws Exception {
        Path dir = temp.resolve("fixed");
        Files.createDirectories(dir);
        PilotObservation o1 = observation("id-1", "ALLOW", 0.1, 100L);
        PilotObservation o2 = observation("id-2", "BLOCK", 0.9, 200L);
        String line1 = PilotObservationJson.write(o1);
        String line2 = PilotObservationJson.write(o2);
        byte[] bytes = (line1 + "\n" + line2 + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(dir.resolve(LocalPilotEvidenceSession.OBSERVATIONS_FILE), bytes);

        PilotSummaryAccumulator a = new PilotSummaryAccumulator("sess");
        a.accept(LocalPilotEvidenceSession.parseObservationLine(line1));
        a.accept(LocalPilotEvidenceSession.parseObservationLine(line2));
        PilotSummary s1 = a.build();

        PilotSummaryAccumulator b = new PilotSummaryAccumulator("sess");
        for (String line : Files.readAllLines(dir.resolve(LocalPilotEvidenceSession.OBSERVATIONS_FILE))) {
            b.accept(LocalPilotEvidenceSession.parseObservationLine(line));
        }
        PilotSummary s2 = b.build();

        assertThat(s1.toJson()).isEqualTo(s2.toJson());
        assertThat(sha256(bytes)).isEqualTo(sha256(Files.readAllBytes(dir.resolve(LocalPilotEvidenceSession.OBSERVATIONS_FILE))));
    }

    @Test
    void recordFailuresDoNotPropagate() throws Exception {
        Path dir = temp.resolve("failopen");
        LocalPilotEvidenceSession session = new LocalPilotEvidenceSession(
            dir, "s-fail", monitorConfig(), new PilotPseudonymizer("pilot-secret-16b!!"));
        session.finalizeSession();
        session.record("id", decision("BLOCK", 0.8), 1L, true);
    }

    private static PilotConfigSnapshot snapshot(String mode, String baseline, String scorerId, String software) {
        return new PilotConfigSnapshot(
            mode, baseline, scorerId, "1", software, "1", "1",
            PilotObservation.EVIDENCE_CLASS, "threshold-policy", 0.2, 0.4, 0.6, 0.8);
    }

    private static PilotConfigSnapshot monitorConfig() {
        return new PilotConfigSnapshot(
            PilotObservation.RUNTIME_MODE_MONITOR,
            "ALLOW_OR_MONITOR",
            "composite",
            "0.4.0",
            "0.4.0",
            "1",
            PilotObservation.SCHEMA_VERSION,
            PilotObservation.EVIDENCE_CLASS,
            "threshold-policy",
            0.2,
            0.4,
            0.6,
            0.8
        );
    }

    private static dev.aisentinel.core.decision.RiskDecision decision(String action, double score) {
        var features = dev.aisentinel.core.model.RequestFeatures.builder()
            .identityHash("id")
            .endpoint("/api/users/{id}")
            .requestsPerWindow(1.0)
            .endpointEntropy(0.1)
            .endpointConcentration(0.2)
            .tokenAgeSeconds(3.0)
            .parameterCount(1)
            .payloadSizeBytes(10L)
            .headerFingerprintHash(123L)
            .ipBucket(4)
            .build();
        return new dev.aisentinel.core.decision.RiskDecision(
            dev.aisentinel.core.policy.EnforcementAction.valueOf(action),
            score,
            score,
            features,
            new dev.aisentinel.core.model.RequestContext(),
            false,
            java.util.Set.of(dev.aisentinel.core.decision.EvaluationStatus.COMPLETE)
        );
    }

    private static PilotObservation observation(String id, String action, double score, long latency) {
        return new PilotObservation(
            PilotObservation.SCHEMA_VERSION,
            id,
            "2026-01-01T00:00:00Z",
            "sess",
            PilotObservation.EVIDENCE_CLASS,
            "pseudo-" + id,
            "/api/users/{id}",
            new PilotObservationFeatures(1, 0.1, 0.2, 3, 1, 10, 123, 4),
            score,
            null,
            List.of("COMPLETE"),
            action,
            false,
            PilotObservation.REQUEST_OUTCOME_CONTINUED,
            PilotObservation.RUNTIME_MODE_MONITOR,
            "ACCEPTED",
            "composite",
            "0.4.0",
            "0.4.0",
            "1",
            latency,
            ""
        );
    }

    private static String sha256(byte[] bytes) {
        return dev.aisentinel.distributed.training.TrainingFingerprintHashes.sha256HexBytes(bytes);
    }
}
