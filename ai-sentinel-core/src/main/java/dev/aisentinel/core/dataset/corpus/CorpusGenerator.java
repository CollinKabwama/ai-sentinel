package dev.aisentinel.core.dataset.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Deterministic Evaluation Kit corpus generator.
 * <p>
 * Compiles a Scenario / Test Plan plus seed and generator build identity into:
 * <ul>
 *   <li>detector-facing {@code events.jsonl} ({@link dev.aisentinel.core.contract.EvaluationEventJson})</li>
 *   <li>Kit {@code corpus-manifest.json}</li>
 *   <li>Kit ground-truth {@code annotations.json} sidecar</li>
 *   <li>replay-compatible {@code manifest.json} ({@link dev.aisentinel.core.dataset.EvaluationDatasetManifest})</li>
 * </ul>
 * Historical {@code ReferenceDatasetGenerator} remains a separate fixed seed artifact path and is not used here.
 * Versioned repository reference corpora live under {@code evaluation/kit-reference/}.
 */
public final class CorpusGenerator {

    private CorpusGenerator() {
    }

    /**
     * Generates a corpus from a scenario file.
     *
     * @param scenarioPath      Evaluation Kit Scenario / Test Plan JSON
     * @param seed              deterministic seed (string form)
     * @param generatorBuildId  concrete generator implementation/build identity
     * @param outputDirectory   empty directory for artifacts
     */
    public static GeneratedCorpus generate(
        Path scenarioPath,
        String seed,
        String generatorBuildId,
        Path outputDirectory
    ) throws IOException {
        Objects.requireNonNull(scenarioPath, "scenarioPath");
        String scenarioJson = Files.readString(scenarioPath, StandardCharsets.UTF_8);
        return generate(scenarioJson, seed, generatorBuildId, outputDirectory);
    }

    /**
     * Generates a corpus from scenario JSON text.
     * Determinism is bound to the exact {@code scenarioJson} bytes supplied here.
     */
    public static GeneratedCorpus generate(
        String scenarioJson,
        String seed,
        String generatorBuildId,
        Path outputDirectory
    ) throws IOException {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        if (seed == null || seed.isBlank()) {
            throw new CorpusGeneratorException("seed is required");
        }
        if (generatorBuildId == null || generatorBuildId.isBlank()) {
            throw new CorpusGeneratorException("generatorBuildId is required");
        }

        ScenarioDocument scenario = ScenarioDocumentParser.parse(scenarioJson);
        if (!"1".equals(scenario.scenarioSchemaVersion())) {
            throw new CorpusGeneratorException(
                "Unsupported scenarioSchemaVersion: " + scenario.scenarioSchemaVersion());
        }
        if (!"1".equals(scenario.featureSchemaVersion())) {
            throw new CorpusGeneratorException(
                "Unsupported featureSchemaVersion: " + scenario.featureSchemaVersion());
        }

        CompiledCorpus compiled = CorpusFamilyCompilers.compile(scenario, seed, generatorBuildId);
        GeneratedCorpus generated = CorpusArtifactWriter.write(
            outputDirectory,
            scenario,
            scenarioJson,
            seed,
            generatorBuildId,
            compiled
        );
        CorpusArtifactWriter.verifyIntegrity(generated);
        return generated;
    }

    /**
     * Re-validates checksums recorded in a previously generated corpus directory.
     */
    public static void verifyChecksums(GeneratedCorpus corpus) throws IOException {
        Objects.requireNonNull(corpus, "corpus");
        CorpusArtifactWriter.verifyIntegrity(corpus);
    }
}
