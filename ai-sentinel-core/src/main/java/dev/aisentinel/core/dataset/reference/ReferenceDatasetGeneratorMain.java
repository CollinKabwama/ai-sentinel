package dev.aisentinel.core.dataset.reference;

import java.nio.file.Path;

/**
 * CLI entrypoint for regenerating the reference synthetic evaluation dataset.
 */
public final class ReferenceDatasetGeneratorMain {

    private ReferenceDatasetGeneratorMain() {
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length > 0
            ? Path.of(args[0]).toAbsolutePath().normalize()
            : ReferenceDatasetGenerator.TRACKED_DATASET_DIRECTORY.toAbsolutePath().normalize();
        ReferenceDatasetGenerator.GeneratedReferenceDataset generated =
            new ReferenceDatasetGenerator().generate(output);
        System.out.printf(
            "referenceDataset output=%s events=%d scenarios=%d identities=%d bytes=%d sha256=%s%n",
            output,
            generated.events().size(),
            generated.scenarioCount(),
            generated.identityCount(),
            generated.totalBytes(),
            generated.eventsSha256()
        );
    }
}
