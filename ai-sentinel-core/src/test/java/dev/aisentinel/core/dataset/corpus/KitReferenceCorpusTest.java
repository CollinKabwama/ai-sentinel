package dev.aisentinel.core.dataset.corpus;

import dev.aisentinel.core.contract.EvaluationEvent;
import dev.aisentinel.core.contract.EvaluationEventJson;
import dev.aisentinel.core.replay.ReplayDataset;
import dev.aisentinel.core.replay.ReplayDatasetLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KitReferenceCorpusTest {

    @Test
    void checkedInReferenceCorpusVerifiesWithNoDrift() throws Exception {
        KitReferenceCorpusMaintainer.verify(repoRoot());
    }

    @Test
    void inventoryListsExactlyTheCheckedInCorpora() throws Exception {
        Path root = KitReferenceCorpusMaintainer.root(repoRoot());
        String inventory = Files.readString(root.resolve("inventory.json"), StandardCharsets.UTF_8);
        KitReferenceCorpusMaintainer.GenerationSpec spec =
            KitReferenceCorpusMaintainer.readGenerationSpec(root.resolve("generation-spec.json"));

        assertThat(spec.entries()).hasSize(11);
        assertThat(inventory).contains("\"inventoryId\":\"kit-reference-corpus\"");
        assertThat(inventory).contains("\"inventoryVersion\":\"1.0.0\"");
        assertThat(inventory).contains("\"representationMode\":\"feature-level\"");

        Set<String> corpusDirsOnDisk;
        try (var stream = Files.list(root.resolve("corpora"))) {
            corpusDirsOnDisk = stream
                .filter(Files::isDirectory)
                .map(path -> "corpora/" + path.getFileName())
                .collect(Collectors.toCollection(HashSet::new));
        }
        assertThat(corpusDirsOnDisk).hasSize(11);

        for (KitReferenceCorpusMaintainer.GenerationEntry entry : spec.entries()) {
            assertThat(inventory).contains("\"corpusPath\":\"" + entry.corpusDirectory() + "\"");
            assertThat(corpusDirsOnDisk).contains(entry.corpusDirectory());
            assertThat(Files.isRegularFile(root.resolve(entry.scenarioFile()))).isTrue();
            assertThat(Files.isRegularFile(
                root.resolve(entry.corpusDirectory()).resolve("events.jsonl"))).isTrue();
            assertThat(Files.isRegularFile(
                root.resolve(entry.corpusDirectory()).resolve("annotations.json"))).isTrue();
            assertThat(Files.isRegularFile(
                root.resolve(entry.corpusDirectory()).resolve("corpus-manifest.json"))).isTrue();
            assertThat(Files.isRegularFile(
                root.resolve(entry.corpusDirectory()).resolve("manifest.json"))).isTrue();
        }
    }

    @Test
    void everyReferenceCorpusLoadsThroughReplayAndKeepsGroundTruthExternal() throws Exception {
        Path root = KitReferenceCorpusMaintainer.root(repoRoot());
        KitReferenceCorpusMaintainer.GenerationSpec spec =
            KitReferenceCorpusMaintainer.readGenerationSpec(root.resolve("generation-spec.json"));
        ReplayDatasetLoader loader = new ReplayDatasetLoader();

        for (KitReferenceCorpusMaintainer.GenerationEntry entry : spec.entries()) {
            Path corpusDir = root.resolve(entry.corpusDirectory());
            ReplayDataset dataset = loader.load(corpusDir);
            assertThat(dataset.events()).isNotEmpty();

            List<String> lines = Files.readAllLines(corpusDir.resolve("events.jsonl"), StandardCharsets.UTF_8)
                .stream()
                .filter(line -> !line.isBlank())
                .toList();
            assertThat(lines).hasSize(dataset.events().size());
            for (String line : lines) {
                EvaluationEventJson.parse(line);
                assertThat(line).doesNotContain("expectedClass");
                assertThat(line).doesNotContain("\"scenarioId\"");
                assertThat(line).doesNotContain("\"corpusId\"");
                assertThat(line).doesNotContain("\"seed\"");
                assertThat(line).doesNotContain("generatorBuildId");
            }

            String annotations = Files.readString(corpusDir.resolve("annotations.json"), StandardCharsets.UTF_8);
            assertThat(annotations).contains("\"expectedClass\"");
            assertThat(annotations.toLowerCase(Locale.ROOT)).doesNotContain("malicious");
            assertThat(annotations).contains("\"category\":\"warmup\"");
        }
    }

    @Test
    void historicalReferenceCorpusRemainsUnchangedAndLoadable() throws Exception {
        Path reference = repoRoot().resolve("evaluation/reference");
        ReplayDataset dataset = new ReplayDatasetLoader().load(
            reference, reference.resolve("annotations.json"));
        assertThat(dataset.events()).hasSize(136);
        assertThat(dataset.manifest().eventsSha256())
            .isEqualTo("1c4178816d33dd460dc233167d5b1e770ba25a6694a772407f2d01dc21e4eded");
    }

    @Test
    void inventoryTamperingIsDetectedOnVerify() throws Exception {
        Path root = KitReferenceCorpusMaintainer.root(repoRoot());
        Path inventory = root.resolve("inventory.json");
        String original = Files.readString(inventory, StandardCharsets.UTF_8);
        try {
            Files.writeString(inventory, original.replace("\"eventCount\":", "\"eventCount\":1"), StandardCharsets.UTF_8);
            assertThatThrownBy(() -> KitReferenceCorpusMaintainer.verify(repoRoot()))
                .isInstanceOf(CorpusGeneratorException.class);
        } finally {
            Files.writeString(inventory, original, StandardCharsets.UTF_8);
        }
    }

    @Test
    void trackedArtifactsContainNoInternalPlanningIdentifiers() throws Exception {
        Path root = KitReferenceCorpusMaintainer.root(repoRoot());
        // Built via concatenation rather than as literal contiguous strings, so this scrub test
        // does not itself introduce the very identifier-shaped substrings it exists to forbid into
        // tracked source.
        String[] patterns = {
            "ENG" + "-",
            "FIND" + "-",
            "TASK" + "-",
            "WAVE" + "-",
            "REVIEW" + "-",
            "REMEDIAT" + "ION",
            "roadmap" + " task",
            "review" + " gate"
        };
        try (var walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).toList();
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String pattern : patterns) {
                    assertThat(text)
                        .as("%s must not contain %s", root.relativize(file), pattern)
                        .doesNotContain(pattern);
                }
            }
        }
    }

    @Test
    void noGeneratedEventAssertsARuntimeEvaluationStatus() throws Exception {
        // Corpus generation never runs a scorer, so no generated event may carry a decision-engine
        // runtime status (e.g. INVALID_SCORE) that only real evaluation/replay can determine.
        Path root = KitReferenceCorpusMaintainer.root(repoRoot());
        KitReferenceCorpusMaintainer.GenerationSpec spec =
            KitReferenceCorpusMaintainer.readGenerationSpec(root.resolve("generation-spec.json"));
        for (KitReferenceCorpusMaintainer.GenerationEntry entry : spec.entries()) {
            Path eventsPath = root.resolve(entry.corpusDirectory()).resolve("events.jsonl");
            for (String line : Files.readAllLines(eventsPath, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                EvaluationEvent event = EvaluationEventJson.parse(line);
                assertThat(event.evaluationStatuses())
                    .as("%s must not assert a runtime evaluation status", entry.corpusDirectory())
                    .isEmpty();
                assertThat(event.anomalyScore()).isNull();
                assertThat(event.policyScore()).isNull();
            }
        }
    }

    @Test
    void legitimateBurstFeaturesAreNotIdenticalToAnomalousBurstFeatures() throws Exception {
        Path root = KitReferenceCorpusMaintainer.root(repoRoot());
        List<String> legitimateLines = Files.readAllLines(
            root.resolve("corpora/kit.legitimate-burst/events.jsonl"), StandardCharsets.UTF_8);
        List<String> abruptLines = Files.readAllLines(
            root.resolve("corpora/kit.abrupt-burst/events.jsonl"), StandardCharsets.UTF_8);

        String legitimateBurstTick = legitimateLines.get(legitimateLines.size() - 1);
        String anomalousBurstTick = abruptLines.get(abruptLines.size() - 1);

        assertThat(legitimateBurstTick).contains("\"requestsPerWindow\":20.0");
        assertThat(anomalousBurstTick).contains("\"requestsPerWindow\":48.0");
        assertThat(legitimateBurstTick).doesNotContain("\"endpointEntropy\":0.2,");
        assertThat(legitimateBurstTick).doesNotContain("\"endpointConcentration\":0.95");
    }

    @Test
    void duplicateScenarioIdInGenerationSpecFailsGeneration(@TempDir Path tempRepo) throws Exception {
        Path root = seedDuplicateGenerationSpec(tempRepo, "kit.duplicate.v1", "kit.duplicate.v1");
        assertThatThrownBy(() -> KitReferenceCorpusMaintainer.generate(tempRepo, root.resolve("out")))
            .isInstanceOf(CorpusGeneratorException.class)
            .hasMessageContaining("Duplicate scenarioId");
    }

    private static Path seedDuplicateGenerationSpec(
        Path tempRepo,
        String scenarioIdA,
        String scenarioIdB
    ) throws Exception {
        Path kitRoot = tempRepo.resolve("evaluation/kit-reference");
        Path scenariosDir = kitRoot.resolve("scenarios");
        Files.createDirectories(scenariosDir);
        Path sourceScenario = repoRoot()
            .resolve("evaluation/kit-reference/scenarios/kit.established-normal.v1.json");
        String scenarioJson = Files.readString(sourceScenario, StandardCharsets.UTF_8);
        Files.writeString(scenariosDir.resolve("a.json"),
            scenarioJson.replace("kit.established-normal.v1", scenarioIdA), StandardCharsets.UTF_8);
        Files.writeString(scenariosDir.resolve("b.json"),
            scenarioJson.replace("kit.established-normal.v1", scenarioIdB), StandardCharsets.UTF_8);
        String spec = "{"
            + "\"generationSpecVersion\":\"1\","
            + "\"inventoryId\":\"dup-check\","
            + "\"inventoryVersion\":\"1.0.0\","
            + "\"generatorContractVersion\":\"1\","
            + "\"generatorBuildId\":\"dup-check@1\","
            + "\"representationMode\":\"feature-level\","
            + "\"entries\":["
            + "{\"scenarioFile\":\"scenarios/a.json\",\"corpusDirectory\":\"corpora/a\",\"seed\":\"s1\"},"
            + "{\"scenarioFile\":\"scenarios/b.json\",\"corpusDirectory\":\"corpora/b\",\"seed\":\"s1\"}"
            + "]}";
        Files.writeString(kitRoot.resolve("generation-spec.json"), spec, StandardCharsets.UTF_8);
        return kitRoot;
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
