package dev.aisentinel.core.dataset.corpus;

import java.nio.file.Path;

/**
 * Repository maintenance entry point for the versioned Kit reference evaluation corpus.
 * <p>
 * Usage:
 * <ul>
 *   <li>{@code verify <repositoryRoot>} — regenerate to a temp directory and fail on drift</li>
 *   <li>{@code write <repositoryRoot>} — regenerate into {@code evaluation/kit-reference/}</li>
 * </ul>
 * Not a product CLI.
 */
public final class KitReferenceCorpusMain {

    private KitReferenceCorpusMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: KitReferenceCorpusMain <verify|write> <repositoryRoot>");
            System.exit(2);
        }
        String mode = args[0];
        Path repositoryRoot = Path.of(args[1]).toAbsolutePath().normalize();
        switch (mode) {
            case "verify" -> {
                KitReferenceCorpusMaintainer.verify(repositoryRoot);
                System.out.println("Kit reference corpus matches deterministic regeneration.");
            }
            case "write" -> {
                Path output = KitReferenceCorpusMaintainer.root(repositoryRoot);
                KitReferenceCorpusMaintainer.generate(repositoryRoot, output);
                System.out.println("Kit reference corpus written to " + output);
            }
            default -> {
                System.err.println("Unknown mode: " + mode + " (expected verify|write)");
                System.exit(2);
            }
        }
    }
}
