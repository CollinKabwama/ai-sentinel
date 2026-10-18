package dev.aisentinel.core.dataset.corpus;

import dev.aisentinel.core.contract.EvaluationEventJson;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorpusGeneratorTest {

    private static final String BUILD_ID = "aisentinel-corpus-generator@test-mvp-1";
    private static final String SEED_A = "42";
    private static final String SEED_B = "43";

    @TempDir
    Path tempDir;

    @Test
    void sameInputsProduceByteIdenticalCorpus() throws Exception {
        String scenario = mvpScenario();
        Path firstDir = tempDir.resolve("first");
        Path secondDir = tempDir.resolve("second");

        GeneratedCorpus first = CorpusGenerator.generate(scenario, SEED_A, BUILD_ID, firstDir);
        GeneratedCorpus second = CorpusGenerator.generate(scenario, SEED_A, BUILD_ID, secondDir);

        assertThat(second.eventsSha256()).isEqualTo(first.eventsSha256());
        assertThat(second.annotationsSha256()).isEqualTo(first.annotationsSha256());
        assertThat(second.corpusId()).isEqualTo(first.corpusId());
        assertThat(second.eventCount()).isEqualTo(first.eventCount());
        assertThat(Files.readAllBytes(second.eventsPath())).isEqualTo(Files.readAllBytes(first.eventsPath()));
        assertThat(Files.readAllBytes(second.annotationsPath()))
            .isEqualTo(Files.readAllBytes(first.annotationsPath()));
        assertThat(Files.readAllBytes(second.corpusManifestPath()))
            .isEqualTo(Files.readAllBytes(first.corpusManifestPath()));
        assertThat(Files.readAllBytes(second.replayManifestPath()))
            .isEqualTo(Files.readAllBytes(first.replayManifestPath()));
    }

    @Test
    void generationIsLocaleIndependent() throws Exception {
        // String.format("%04d"/"%03d") without an explicit Locale renders non-ASCII digits under
        // certain default locales (for example Arabic-Indic), which would make eventId/identityKey
        // -- and therefore eventsSha256 and corpusId -- depend on the JVM's default locale rather
        // than only on scenario + seed + generator identity. Reproduced empirically before fixing:
        // String.format("%04d", 5) under Locale.forLanguageTag("ar-SA-u-nu-arab") produces the
        // Arabic-Indic digits for "0005", not ASCII "0005".
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            GeneratedCorpus baseline = CorpusGenerator.generate(
                mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("locale-ascii"));

            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-SA-u-nu-arab"));
            GeneratedCorpus underArabicIndicLocale = CorpusGenerator.generate(
                mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("locale-arabic-indic"));

            assertThat(underArabicIndicLocale.eventsSha256()).isEqualTo(baseline.eventsSha256());
            assertThat(underArabicIndicLocale.corpusId()).isEqualTo(baseline.corpusId());
            List<String> lines = Files.readAllLines(underArabicIndicLocale.eventsPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.isBlank()) {
                    assertThat(EvaluationEventJson.parse(line).eventId()).matches("evt-[0-9a-f]+-[0-9]{4}");
                }
            }
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    void differentSeedProducesDifferentCorpus() throws Exception {
        String scenario = mvpScenario();
        GeneratedCorpus first = CorpusGenerator.generate(scenario, SEED_A, BUILD_ID, tempDir.resolve("a"));
        GeneratedCorpus second = CorpusGenerator.generate(scenario, SEED_B, BUILD_ID, tempDir.resolve("b"));

        assertThat(second.eventsSha256()).isNotEqualTo(first.eventsSha256());
        assertThat(second.corpusId()).isNotEqualTo(first.corpusId());
    }

    @Test
    void differentGeneratorBuildIdChangesProvenanceAndIdentity() throws Exception {
        String scenario = mvpScenario();
        GeneratedCorpus first = CorpusGenerator.generate(
            scenario, SEED_A, BUILD_ID, tempDir.resolve("build-a"));
        GeneratedCorpus second = CorpusGenerator.generate(
            scenario, SEED_A, BUILD_ID + "-alt", tempDir.resolve("build-b"));

        // generatorBuildId is a declared determinism axis (docs/contracts/EVALUATION_KIT.md §3):
        // changing it alone, with the same scenario and seed, must change both the recorded
        // provenance and the derived corpus identity, not just the manifest field value.
        assertThat(second.corpusId()).isNotEqualTo(first.corpusId());
        assertThat(second.eventsSha256()).isNotEqualTo(first.eventsSha256());
        assertThat(second.generatorBuildId()).isNotEqualTo(first.generatorBuildId());
    }

    @Test
    void semanticallyChangedScenarioChangesCorpusIdentity() throws Exception {
        String scenario = mvpScenario();
        String changed = scenario.replace(
            "\"scenarioVersion\": \"1.0.0\"", "\"scenarioVersion\": \"1.0.1\"");
        assertThat(changed).isNotEqualTo(scenario);

        GeneratedCorpus first = CorpusGenerator.generate(scenario, SEED_A, BUILD_ID, tempDir.resolve("sem-a"));
        GeneratedCorpus second = CorpusGenerator.generate(changed, SEED_A, BUILD_ID, tempDir.resolve("sem-b"));

        assertThat(second.corpusId()).isNotEqualTo(first.corpusId());
        assertThat(second.scenarioSha256()).isNotEqualTo(first.scenarioSha256());
    }

    @Test
    void whitespaceOnlyScenarioChangeAltersScenarioChecksumButNotCorpusIdentity() throws Exception {
        // Documents the actual, intentional behavior under the "exact scenario bytes" determinism
        // rule: scenarioSha256 is a byte-exact provenance/integrity signal over the supplied JSON
        // text, while corpusId is derived from *parsed* scenarioId/scenarioVersion + seed +
        // generatorContractVersion + generatorBuildId. A purely cosmetic (whitespace-only) edit to
        // the scenario file therefore changes scenarioSha256 (the bytes differ) but leaves
        // corpusId, event content, and all checksums that depend on parsed fields unchanged. This
        // is intentional: corpusId identifies the logical scenario+inputs combination, not the
        // literal formatting of the authoring file. If this distinction is ever expected to
        // collapse, that is a deliberate contract change, not a bug fix.
        String scenario = mvpScenario();
        String reformatted = scenario.replace("\n", "\n  ");
        assertThat(reformatted).isNotEqualTo(scenario);

        GeneratedCorpus first = CorpusGenerator.generate(scenario, SEED_A, BUILD_ID, tempDir.resolve("ws-a"));
        GeneratedCorpus second = CorpusGenerator.generate(reformatted, SEED_A, BUILD_ID, tempDir.resolve("ws-b"));

        assertThat(second.scenarioSha256()).isNotEqualTo(first.scenarioSha256());
        assertThat(second.corpusId()).isEqualTo(first.corpusId());
        assertThat(second.eventsSha256()).isEqualTo(first.eventsSha256());
    }

    @Test
    void annotationTamperingIsDetected() throws Exception {
        GeneratedCorpus corpus = CorpusGenerator.generate(
            mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("tamper-annotations"));
        CorpusGenerator.verifyChecksums(corpus);

        Files.writeString(corpus.annotationsPath(),
            Files.readString(corpus.annotationsPath(), StandardCharsets.UTF_8)
                .replace("\"benign\"", "\"anomalous\""),
            StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CorpusGenerator.verifyChecksums(corpus))
            .isInstanceOf(CorpusGeneratorException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    void generatedFeaturesContainNoDirectMaliciousnessOrAnomalyMarker() throws Exception {
        // Ground truth (expectedClass) lives only in the annotations sidecar. The detector-facing
        // feature vector itself must not smuggle an equivalent signal under another name (for
        // example a feature that is literally 1.0 only for "the" anomalous event and 0.0
        // otherwise, which would let a consumer reconstruct labels from "detector input").
        GeneratedCorpus corpus = CorpusGenerator.generate(
            mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("no-label-feature"));
        List<String> lines = Files.readAllLines(corpus.eventsPath(), StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank())
            .toList();
        for (String line : lines) {
            assertThat(line.toLowerCase(java.util.Locale.ROOT)).doesNotContain("malicious");
            assertThat(line).doesNotContain("\"expectedClass\"");
        }
    }

    @Test
    void detectorFacingEventsExcludeGroundTruthAndKitIdentity() throws Exception {
        GeneratedCorpus corpus = CorpusGenerator.generate(
            mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("iso"));

        List<String> lines = Files.readAllLines(corpus.eventsPath(), StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank())
            .toList();
        assertThat(lines).isNotEmpty();
        for (String line : lines) {
            EvaluationEventJson.parse(line);
            assertThat(line).doesNotContain("expectedClass");
            assertThat(line).doesNotContain("groundTruth");
            assertThat(line).doesNotContain("\"scenarioId\"");
            assertThat(line).doesNotContain("\"corpusId\"");
            assertThat(line).doesNotContain("\"resultId\"");
            assertThat(line).doesNotContain("\"seed\"");
            assertThat(line).doesNotContain("generatorContractVersion");
            assertThat(line).doesNotContain("generatorBuildId");
            assertThat(line).doesNotContain("\"labels\"");
            assertThat(line).doesNotContain("\"annotation\"");
        }
    }

    @Test
    void kitManifestAndGroundTruthMatchContractShape() throws Exception {
        GeneratedCorpus corpus = CorpusGenerator.generate(
            mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("kit"));

        String manifest = Files.readString(corpus.corpusManifestPath(), StandardCharsets.UTF_8);
        assertThat(manifest).contains("\"corpusSchemaVersion\":\"1\"");
        assertThat(manifest).contains("\"generatorContractVersion\":\"1\"");
        assertThat(manifest).contains("\"generatorBuildId\":\"" + BUILD_ID + "\"");
        assertThat(manifest).contains("\"seed\":\"" + SEED_A + "\"");
        assertThat(manifest).contains("\"representationMode\":\"feature-level\"");
        assertThat(manifest).contains("\"role\":\"events\"");
        assertThat(manifest).contains("\"role\":\"annotations\"");
        assertThat(manifest).contains("\"sha256\":\"" + corpus.eventsSha256() + "\"");
        assertThat(manifest).contains("\"sha256\":\"" + corpus.annotationsSha256() + "\"");
        assertThat(manifest).contains("\"eventCount\":" + corpus.eventCount());

        String annotations = Files.readString(corpus.annotationsPath(), StandardCharsets.UTF_8);
        assertThat(annotations).contains("\"annotationSchemaVersion\":\"1\"");
        assertThat(annotations).contains("\"corpusId\":\"" + corpus.corpusId() + "\"");
        assertThat(annotations).contains("\"expectedClass\":\"benign\"");
        assertThat(annotations).contains("\"expectedClass\":\"anomalous\"");
        assertThat(annotations).doesNotContain("malicious");
    }

    @Test
    void corpusManifestChecksumTamperingIsDetectedIndependentlyOfReplayManifest() throws Exception {
        // Kit corpus-manifest.json and replay-compatible manifest.json each record their own copy
        // of eventsSha256. Tampering ONLY the Kit manifest (leaving events.jsonl and the replay
        // manifest untouched) must still be caught -- the two manifests are cross-checked
        // independently against events.jsonl, not merely against each other.
        GeneratedCorpus corpus = CorpusGenerator.generate(
            mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("tamper-corpus-manifest"));
        CorpusGenerator.verifyChecksums(corpus);

        String tamperedManifest = Files.readString(corpus.corpusManifestPath(), StandardCharsets.UTF_8)
            .replace(corpus.eventsSha256(), "f".repeat(64));
        Files.writeString(corpus.corpusManifestPath(), tamperedManifest, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CorpusGenerator.verifyChecksums(corpus))
            .isInstanceOf(CorpusGeneratorException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    void replayManifestChecksumTamperingIsDetectedIndependentlyOfCorpusManifest() throws Exception {
        GeneratedCorpus corpus = CorpusGenerator.generate(
            mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("tamper-replay-manifest"));
        CorpusGenerator.verifyChecksums(corpus);

        String tamperedReplayManifest = Files.readString(corpus.replayManifestPath(), StandardCharsets.UTF_8)
            .replace(corpus.eventsSha256(), "f".repeat(64));
        Files.writeString(corpus.replayManifestPath(), tamperedReplayManifest, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CorpusGenerator.verifyChecksums(corpus))
            .isInstanceOf(CorpusGeneratorException.class)
            .hasMessageContaining("Replay manifest");
    }

    @Test
    void checksumTamperingIsDetected() throws Exception {
        GeneratedCorpus corpus = CorpusGenerator.generate(
            mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("tamper"));
        CorpusGenerator.verifyChecksums(corpus);

        Files.writeString(corpus.eventsPath(),
            Files.readString(corpus.eventsPath(), StandardCharsets.UTF_8) + " ",
            StandardCharsets.UTF_8);

        assertThatThrownBy(() -> CorpusGenerator.verifyChecksums(corpus))
            .isInstanceOf(CorpusGeneratorException.class)
            .hasMessageContaining("checksum");
    }

    @Test
    void generatedEventsLoadThroughReplayDatasetLoader() throws Exception {
        GeneratedCorpus corpus = CorpusGenerator.generate(
            mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("replay"));

        ReplayDataset dataset = new ReplayDatasetLoader().load(corpus.outputDirectory());
        assertThat(dataset.events()).hasSize(corpus.eventCount());
        assertThat(dataset.manifest().eventsSha256()).isEqualTo(corpus.eventsSha256());

        List<String> eventIds = dataset.events().stream()
            .map(event -> event.replayInput().eventId())
            .toList();
        assertThat(eventIds).doesNotHaveDuplicates();
        assertThat(eventIds.get(0)).isLessThan(eventIds.get(eventIds.size() - 1));
    }

    @Test
    void eventOrderingAndIdentitiesAreDeterministic() throws Exception {
        GeneratedCorpus corpus = CorpusGenerator.generate(
            mvpScenario(), SEED_A, BUILD_ID, tempDir.resolve("order"));
        List<String> lines = Files.readAllLines(corpus.eventsPath(), StandardCharsets.UTF_8).stream()
            .filter(line -> !line.isBlank())
            .toList();
        assertThat(lines).hasSize(5 + 8);
        assertThat(EvaluationEventJson.parse(lines.get(0)).eventId()).endsWith("-0001");
        assertThat(EvaluationEventJson.parse(lines.get(lines.size() - 1)).eventId()).endsWith("-0013");
    }

    @Test
    void unsupportedFamilyFailsClearly() throws Exception {
        String scenario = mvpScenario().replace(
            "\"family\": \"warmup-then-burst\"",
            "\"family\": \"not-a-real-family\"");
        assertThatThrownBy(() -> CorpusGenerator.generate(scenario, SEED_A, BUILD_ID, tempDir.resolve("bad-family")))
            .isInstanceOf(CorpusGeneratorException.class)
            .hasMessageContaining("Unsupported scenario family");
    }

    @Test
    void missingScenarioFieldsFailClearly() {
        assertThatThrownBy(() -> CorpusGenerator.generate(
            "{\"scenarioSchemaVersion\":\"1\"}", SEED_A, BUILD_ID, tempDir.resolve("bad-json")))
            .isInstanceOf(CorpusGeneratorException.class);
    }

    @Test
    void blankSeedRejected() throws Exception {
        String scenario = mvpScenario();
        assertThatThrownBy(() -> CorpusGenerator.generate(
            scenario, " ", BUILD_ID, tempDir.resolve("blank-seed")))
            .isInstanceOf(CorpusGeneratorException.class)
            .hasMessageContaining("seed");
    }

    @Test
    void historicalReferenceCorpusStillLoads() throws Exception {
        Path reference = repoRoot().resolve("evaluation/reference");
        ReplayDataset dataset = new ReplayDatasetLoader().load(reference, reference.resolve("annotations.json"));
        assertThat(dataset.events()).hasSize(136);
        assertThat(dataset.manifest().eventsSha256())
            .isEqualTo("1c4178816d33dd460dc233167d5b1e770ba25a6694a772407f2d01dc21e4eded");
    }

    private static String mvpScenario() throws Exception {
        Path fixture = repoRoot().resolve(
            "docs/contracts/fixtures/evaluation-kit/valid/scenario.warmup-then-burst.mvp.json");
        return Files.readString(fixture, StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml")) && Files.exists(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }
}