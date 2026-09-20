package dev.aisentinel.core.replay;

import dev.aisentinel.core.dataset.reference.ReferenceDatasetGenerator;

import java.nio.file.Path;

/**
 * CLI entrypoint for replaying the tracked reference dataset.
 */
public final class ReferenceDatasetReplayMain {

    private ReferenceDatasetReplayMain() {
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length > 0
            ? Path.of(args[0]).toAbsolutePath().normalize()
            : Path.of("build/reference-replay").toAbsolutePath().normalize();
        ReplayConfiguration configuration = ReplayConfiguration.referenceDefaults();
        ReplayDataset dataset = new ReplayDatasetLoader().load(
            ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY,
            ReferenceDatasetGenerator.TRACKED_ANNOTATIONS_FILE
        );
        ReplayEngine.ReplayRun run = new ReplayEngine().run(dataset, configuration, output);
        ReplayOutputValidator.ValidationSummary validation = new ReplayOutputValidator().validate(output, dataset, configuration);
        System.out.printf(
            "referenceReplay output=%s results=%d identities=%d scorer=%s datasetSha256=%s resultsSha256=%s%n",
            output,
            run.resultCount(),
            dataset.events().stream().map(event -> event.replayInput().identityKey()).distinct().count(),
            configuration.scorer().scorerId(),
            dataset.eventsSha256(),
            validation.resultsSha256()
        );
    }
}
