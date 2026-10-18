package dev.aisentinel.core.evaluation;

import dev.aisentinel.core.model.FeatureSchema;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.replay.ReplayConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceIsolationForestScorerTest {

    @Test
    void sameInputAndSeedProducesSameScores() {
        ReferenceIsolationForestConfig config = ReferenceIsolationForestConfig.FROZEN;
        ReferenceIsolationForestScorer left = new ReferenceIsolationForestScorer(config);
        ReferenceIsolationForestScorer right = new ReferenceIsolationForestScorer(config);

        RequestFeatures[] sequence = sampleSequence();
        double[] leftScores = new double[sequence.length];
        double[] rightScores = new double[sequence.length];
        for (int i = 0; i < sequence.length; i++) {
            leftScores[i] = left.score(sequence[i]);
            left.update(sequence[i]);
            rightScores[i] = right.score(sequence[i]);
            right.update(sequence[i]);
        }

        assertThat(leftScores).containsExactly(rightScores);
        assertThat(left.modelTrained()).isTrue();
        assertThat(right.modelTrained()).isTrue();
    }

    @Test
    void consumesSixDimensionalStatisticalProjection() {
        ReferenceIsolationForestScorer scorer =
            new ReferenceIsolationForestScorer(ReferenceIsolationForestConfig.FROZEN);
        RequestFeatures features = features(1.0, 0.5, 0.25, 10.0, 3, 128L);
        double[] statistical = features.toStatisticalArray();
        assertThat(statistical).hasSize(FeatureSchema.STATISTICAL_DIMENSION);
        assertThat(statistical).hasSize(6);
        assertThat(features.toIsolationForestArray()).hasSize(5);

        for (int i = 0; i < 4; i++) {
            scorer.score(features);
            scorer.update(features);
        }
        assertThat(scorer.modelTrained()).isTrue();
        double score = scorer.score(features);
        assertThat(score).isBetween(0.0, 1.0);
    }

    @Test
    void scorerSurfaceDoesNotAcceptGroundTruthTypes() throws Exception {
        Class<?> scorer = ReferenceIsolationForestScorer.class;
        for (var method : scorer.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                String name = parameter.getName().toLowerCase(Locale.ROOT);
                assertThat(name)
                    .as("method %s parameter %s", method.getName(), parameter.getName())
                    .doesNotContain("annotation")
                    .doesNotContain("groundtruth")
                    .doesNotContain("expectedclass")
                    .doesNotContain("label");
            }
        }
        assertThat(scorer.getInterfaces()).containsExactly(
            Class.forName("dev.aisentinel.core.scoring.AnomalyScorer"));
    }

    @Test
    void configurationDigestChangesWhenSeedTreesDepthOrThresholdChange() {
        ReferenceIsolationForestConfig base = ReferenceIsolationForestConfig.FROZEN;
        String baseDigest = base.configurationDigestHex();
        assertThat(baseDigest).matches("[0-9a-f]{64}");

        assertThat(base.withRandomSeed(99L).configurationDigestHex()).isNotEqualTo(baseDigest);
        assertThat(base.withNumTrees(50).configurationDigestHex()).isNotEqualTo(baseDigest);
        assertThat(base.withMaxDepth(5).configurationDigestHex()).isNotEqualTo(baseDigest);
        assertThat(base.withAnomalyThreshold(0.6d).configurationDigestHex()).isNotEqualTo(baseDigest);
    }

    @Test
    void configurationDigestChangesWhenMinTrainingSamplesOrFallbackScoreChange() {
        ReferenceIsolationForestConfig base = ReferenceIsolationForestConfig.FROZEN;
        String baseDigest = base.configurationDigestHex();

        ReferenceIsolationForestConfig differentMinSamples = new ReferenceIsolationForestConfig(
            base.numTrees(), base.maxDepth(), base.randomSeed(),
            base.minTrainingSamples() + 1, base.fallbackScore(), base.anomalyThreshold());
        assertThat(differentMinSamples.configurationDigestHex()).isNotEqualTo(baseDigest);

        ReferenceIsolationForestConfig differentFallback = new ReferenceIsolationForestConfig(
            base.numTrees(), base.maxDepth(), base.randomSeed(),
            base.minTrainingSamples(), base.fallbackScore() + 0.05d, base.anomalyThreshold());
        assertThat(differentFallback.configurationDigestHex()).isNotEqualTo(baseDigest);
    }

    @Test
    void endpointConcentrationIsIncludedAtItsStatisticalArrayPositionAndAffectsScoring() {
        // toStatisticalArray() order: requestsPerWindow, endpointEntropy, endpointConcentration,
        // tokenAgeSeconds, parameterCount, payloadSizeBytes — index 2 is endpointConcentration.
        RequestFeatures probe = features(2.0, 0.3, 0.77, 12.0, 4, 256L);
        assertThat(probe.toStatisticalArray()[2]).isEqualTo(0.77d);

        // Train on a population that varies ONLY in endpointConcentration; if the value were
        // dropped (e.g. by accidentally consuming toIsolationForestArray()), these two models
        // would be trained on identical 5-feature data and could not differ structurally.
        ReferenceIsolationForestScorer low =
            new ReferenceIsolationForestScorer(ReferenceIsolationForestConfig.FROZEN);
        ReferenceIsolationForestScorer high =
            new ReferenceIsolationForestScorer(ReferenceIsolationForestConfig.FROZEN);
        for (int i = 0; i < 4; i++) {
            RequestFeatures lowConcentration = features(2.0 + i, 0.3, 0.05 * i, 12.0, 4, 256L);
            RequestFeatures highConcentration = features(2.0 + i, 0.3, 0.9 + 0.02 * i, 12.0, 4, 256L);
            low.update(lowConcentration);
            high.update(highConcentration);
        }
        assertThat(low.modelTrained()).isTrue();
        assertThat(high.modelTrained()).isTrue();

        RequestFeatures query = features(2.0, 0.3, 0.5, 12.0, 4, 256L);
        double scoreAgainstLowConcentrationModel = low.score(query);
        double scoreAgainstHighConcentrationModel = high.score(query);
        assertThat(scoreAgainstLowConcentrationModel)
            .as("a model trained only on low endpointConcentration values must score a "
                + "mid-range-concentration query differently than a model trained only on "
                + "high endpointConcentration values, proving the dimension is consumed")
            .isNotEqualTo(scoreAgainstHighConcentrationModel);
    }

    @TempDir
    Path tempDir;

    @Test
    void alternativeEvaluationProducesFiveArtifactsAndDistinctRunId() throws Exception {
        Path corpus = kitCorpus("kit.established-normal");
        Path statisticalOut = tempDir.resolve("statistical");
        Path ifOut = tempDir.resolve("isolation-forest-reference");

        GeneratedCorpusEvaluationResult statistical =
            new GeneratedCorpusDetectionEvaluator().evaluate(
                corpus,
                ReplayConfiguration.referenceDefaults(),
                new DetectionClassificationConfiguration(0.5d),
                statisticalOut
            );

        ReferenceIsolationForestConfig ifConfig = ReferenceIsolationForestConfig.FROZEN;
        ReferenceIsolationForestScorer scorer = new ReferenceIsolationForestScorer(ifConfig);
        GeneratedCorpusEvaluationResult alternative =
            ReferenceIsolationForestReplayBindings.evaluator(scorer).evaluate(
                corpus,
                ReferenceIsolationForestReplayBindings.configuration(ifConfig),
                new DetectionClassificationConfiguration(ifConfig.anomalyThreshold()),
                ifOut
            );

        assertFiveArtifacts(statisticalOut);
        assertFiveArtifacts(ifOut);
        assertThat(scorer.modelTrained()).isTrue();

        String statisticalRunId = runId(statistical);
        String ifRunId = runId(alternative);
        assertThat(ifRunId).isNotEqualTo(statisticalRunId);
        assertThat(alternative.detectionRun().evidence().replay().scorerId())
            .isEqualTo(ReferenceIsolationForestConfig.SCORER_ID);
    }

    @Test
    void comparisonAcceptsStatisticalVersusIsolationForestAndOmitsWinnerLanguage() throws Exception {
        Path corpus = kitCorpus("kit.abrupt-burst");
        Path statisticalOut = tempDir.resolve("stat");
        Path ifOut = tempDir.resolve("ifr");
        Path comparisonOut = tempDir.resolve("comparison");

        new GeneratedCorpusDetectionEvaluator().evaluate(
            corpus,
            ReplayConfiguration.referenceDefaults(),
            new DetectionClassificationConfiguration(0.5d),
            statisticalOut
        );

        ReferenceIsolationForestConfig ifConfig = ReferenceIsolationForestConfig.FROZEN;
        ReferenceIsolationForestReplayBindings.evaluator(new ReferenceIsolationForestScorer(ifConfig))
            .evaluate(
                corpus,
                ReferenceIsolationForestReplayBindings.configuration(ifConfig),
                new DetectionClassificationConfiguration(ifConfig.anomalyThreshold()),
                ifOut
            );

        EvaluationComparisonResult comparison = new EvaluationComparisonEngine().compare(
            statisticalOut, ifOut, comparisonOut);
        assertThat(comparison.compatible()).isTrue();
        assertThat(comparison.eventsCompared()).isGreaterThan(0);

        String json = Files.readString(comparison.comparisonJson(), StandardCharsets.UTF_8);
        String html = Files.readString(comparison.comparisonHtml(), StandardCharsets.UTF_8);
        assertThat(json).doesNotContain("\"winner\"");
        assertThat(html.toLowerCase(Locale.ROOT)).contains("factual deltas only");
        assertThat(html.toLowerCase(Locale.ROOT)).contains("not a winner");
        assertThat(json.toLowerCase(Locale.ROOT)).contains("factual deltas only");
        assertThat(json.toLowerCase(Locale.ROOT)).doesNotContain("\"superior\"");
        assertThat(json.toLowerCase(Locale.ROOT)).doesNotContain("\"ranking\"");
        assertThat(html.toLowerCase(Locale.ROOT)).doesNotContain("recommended detector");
    }

    @Test
    void preexistingOutputIsRejected() throws Exception {
        Path existing = tempDir.resolve("exists");
        Files.createDirectories(existing);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = SameFrameworkDetectorComparisonCli.run(
            new String[] {"--output", existing.toString()},
            new PrintStream(stdout, true, StandardCharsets.UTF_8),
            new PrintStream(stderr, true, StandardCharsets.UTF_8),
            repositoryRoot()
        );
        assertThat(code).isEqualTo(SameFrameworkDetectorComparisonCli.EXIT_FAILURE);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).contains("already exists");
    }

    private static void assertFiveArtifacts(Path directory) throws Exception {
        assertThat(directory.resolve("kit-evaluation-result.json")).exists();
        assertThat(directory.resolve("event-inspection.json")).exists();
        assertThat(directory.resolve("evaluation.json")).exists();
        assertThat(directory.resolve("evaluation.md")).exists();
        assertThat(directory.resolve("evaluation-report.html")).exists();
    }

    private static String runId(GeneratedCorpusEvaluationResult result) {
        GeneratedCorpusProvenance provenance = result.provenance();
        DetectionEvaluationEvidence.ReplayProvenance replay = result.detectionRun().evidence().replay();
        return EvaluationRunIdentity.evaluationRunId(
            "generated-corpus",
            provenance.corpusId(),
            provenance.eventsSha256(),
            provenance.annotationsSha256(),
            provenance.featureSchemaVersion(),
            provenance.evaluationEventSchemaVersion(),
            replay.configurationFingerprint(),
            replay.scorerId(),
            replay.scorerVersion(),
            replay.policyId(),
            replay.policyVersion(),
            result.detectionRun().metrics().classification().anomalyThreshold(),
            "1"
        );
    }

    private static RequestFeatures[] sampleSequence() {
        RequestFeatures[] sequence = new RequestFeatures[8];
        for (int i = 0; i < sequence.length; i++) {
            sequence[i] = features(
                1.0 + i,
                0.1 * i,
                0.05 * i,
                5.0 + i,
                i,
                64L + i
            );
        }
        return sequence;
    }

    private static RequestFeatures features(
        double requestsPerWindow,
        double endpointEntropy,
        double endpointConcentration,
        double tokenAgeSeconds,
        int parameterCount,
        long payloadSizeBytes
    ) {
        return RequestFeatures.builder()
            .identityHash("id-a")
            .endpoint("/api/test")
            .timestampMillis(1_700_000_000_000L)
            .requestsPerWindow(requestsPerWindow)
            .endpointEntropy(endpointEntropy)
            .endpointConcentration(endpointConcentration)
            .tokenAgeSeconds(tokenAgeSeconds)
            .parameterCount(parameterCount)
            .payloadSizeBytes(payloadSizeBytes)
            .headerFingerprintHash(1L)
            .ipBucket(1)
            .build();
    }

    private static Path kitCorpus(String name) {
        return repositoryRoot().resolve("evaluation/kit-reference/corpora").resolve(name);
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve("evaluation/kit-reference/corpora"))) {
            return cwd;
        }
        Path parent = cwd.getParent();
        if (parent != null && Files.isDirectory(parent.resolve("evaluation/kit-reference/corpora"))) {
            return parent;
        }
        throw new IllegalStateException("repository root with kit-reference corpora not found from " + cwd);
    }
}
