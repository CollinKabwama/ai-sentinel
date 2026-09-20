package dev.aisentinel.core.evaluation;

import java.nio.file.Path;
import java.util.List;

/**
 * Maintainer CLI for Official Detection Reference Baseline lifecycle governance.
 * <p>
 * Official create/approve/reject/promote/rollback paths use explicit arguments only.
 * Approver metadata is declarative: SUPPLIED APPROVER IDENTIFIER != VERIFIED HUMAN IDENTITY.
 * No threshold tuning, force, overwrite, or skip-validation options are exposed.
 */
public final class ReferenceDetectionBaselineLifecycleMain {

    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_INVALID_USAGE = 1;
    public static final int EXIT_LIFECYCLE_FAILURE = 2;

    private ReferenceDetectionBaselineLifecycleMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            System.err.println(usage());
            System.exit(args.length == 0 ? EXIT_INVALID_USAGE : EXIT_SUCCESS);
            return;
        }
        String command = args[0];
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);
        DetectionReferenceBaselineLifecycle lifecycle = new DetectionReferenceBaselineLifecycle();
        try {
            switch (command) {
                case "create-candidate" -> {
                    CreateArgs parsed = CreateArgs.parse(rest);
                    Path official = ReferenceDetectionBaselineVerifyMain.locateTrackedPath(parsed.baselineDirectory);
                    DetectionReferenceBaselineLifecycleResult result =
                        lifecycle.createCandidate(official, parsed.candidateId);
                    printResult(result);
                }
                case "approve-candidate" -> {
                    DecisionArgs parsed = DecisionArgs.parse(rest, true);
                    Path official = ReferenceDetectionBaselineVerifyMain.locateTrackedPath(parsed.baselineDirectory);
                    printResult(lifecycle.approveCandidate(
                        official, parsed.candidateId, parsed.rationale, parsed.approver
                    ));
                }
                case "reject-candidate" -> {
                    DecisionArgs parsed = DecisionArgs.parse(rest, true);
                    Path official = ReferenceDetectionBaselineVerifyMain.locateTrackedPath(parsed.baselineDirectory);
                    printResult(lifecycle.rejectCandidate(
                        official, parsed.candidateId, parsed.rationale, parsed.approver
                    ));
                }
                case "promote-candidate" -> {
                    PromoteArgs parsed = PromoteArgs.parse(rest);
                    Path official = ReferenceDetectionBaselineVerifyMain.locateTrackedPath(parsed.baselineDirectory);
                    printResult(lifecycle.promoteCandidate(official, parsed.candidateId));
                }
                case "rollback" -> {
                    RollbackArgs parsed = RollbackArgs.parse(rest);
                    Path official = ReferenceDetectionBaselineVerifyMain.locateTrackedPath(parsed.baselineDirectory);
                    printResult(lifecycle.rollback(
                        official, parsed.historyId, parsed.rationale, parsed.approver
                    ));
                }
                case "list-history" -> {
                    Path official = ReferenceDetectionBaselineVerifyMain.locateTrackedPath(
                        ListHistoryArgs.parse(rest).baselineDirectory
                    );
                    List<String> ids = lifecycle.listHistoryIds(official);
                    System.out.println("historyCount=" + ids.size());
                    for (String id : ids) {
                        System.out.println(id);
                    }
                }
                default -> {
                    System.err.println("unsupported command: " + command + "\n" + usage());
                    System.exit(EXIT_INVALID_USAGE);
                }
            }
            System.exit(EXIT_SUCCESS);
        } catch (DetectionReferenceBaselineLifecycleException e) {
            System.err.println("lifecycleFailure code=" + e.code() + " detail=" + e.getMessage());
            System.exit(EXIT_LIFECYCLE_FAILURE);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(EXIT_INVALID_USAGE);
        }
    }

    private static void printResult(DetectionReferenceBaselineLifecycleResult result) {
        System.out.println(
            "detectionReferenceBaselineLifecycle action=" + result.action()
                + " candidateId=" + result.candidateId()
                + " historyId=" + result.historyId()
                + " comparisonStatus=" + (result.comparisonStatus() == null ? "" : result.comparisonStatus().name())
                + " driftEntries=" + result.driftEntries()
                + " governanceRecordSha256=" + result.governanceRecordSha256()
                + " detail=" + result.detail()
        );
    }

    private static String usage() {
        return """
            Usage: ReferenceDetectionBaselineLifecycleMain <command> [options]

            Commands:
              create-candidate --candidate-id <id> [--baseline <dir>]
              approve-candidate --candidate-id <id> --rationale <text> --approver <id> [--baseline <dir>]
              reject-candidate --candidate-id <id> --rationale <text> --approver <id> [--baseline <dir>]
              promote-candidate --candidate-id <id> [--baseline <dir>]
              rollback --history-id <id> --rationale <text> --approver <id> [--baseline <dir>]
              list-history [--baseline <dir>]

            Official create uses DetectionReferenceBaselineConfiguration.officialReference() (threshold 0.5).
            No --force / --overwrite / --threshold options are provided.
            Exit codes: 0=success, 1=invalid usage, 2=lifecycle failure.
            DRIFT != APPROVAL. CANDIDATE != OFFICIAL BASELINE. PROMOTION != PRODUCTION DEPLOYMENT.
            """;
    }

    private static Path defaultBaseline() {
        return DetectionReferenceBaselineLifecycleSchemas.TRACKED_BASELINE_DIRECTORY;
    }

    private record CreateArgs(Path baselineDirectory, String candidateId) {
        static CreateArgs parse(String[] args) {
            Path baseline = defaultBaseline();
            String candidateId = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--baseline".equals(arg)) {
                    baseline = Path.of(requireValue(args, ++i, "--baseline"));
                } else if (arg.startsWith("--baseline=")) {
                    baseline = Path.of(arg.substring("--baseline=".length()).trim());
                } else if ("--candidate-id".equals(arg)) {
                    candidateId = requireValue(args, ++i, "--candidate-id");
                } else if (arg.startsWith("--candidate-id=")) {
                    candidateId = arg.substring("--candidate-id=".length()).trim();
                } else {
                    throw new IllegalArgumentException("unsupported argument: " + arg + "\n" + usage());
                }
            }
            if (candidateId == null || candidateId.isBlank()) {
                throw new IllegalArgumentException("missing --candidate-id\n" + usage());
            }
            return new CreateArgs(baseline, candidateId);
        }
    }

    private record DecisionArgs(Path baselineDirectory, String candidateId, String rationale, String approver) {
        static DecisionArgs parse(String[] args, boolean unused) {
            Path baseline = defaultBaseline();
            String candidateId = null;
            String rationale = null;
            String approver = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--baseline".equals(arg)) {
                    baseline = Path.of(requireValue(args, ++i, "--baseline"));
                } else if (arg.startsWith("--baseline=")) {
                    baseline = Path.of(arg.substring("--baseline=".length()).trim());
                } else if ("--candidate-id".equals(arg)) {
                    candidateId = requireValue(args, ++i, "--candidate-id");
                } else if (arg.startsWith("--candidate-id=")) {
                    candidateId = arg.substring("--candidate-id=".length()).trim();
                } else if ("--rationale".equals(arg)) {
                    rationale = requireValue(args, ++i, "--rationale");
                } else if (arg.startsWith("--rationale=")) {
                    rationale = arg.substring("--rationale=".length()).trim();
                } else if ("--approver".equals(arg)) {
                    approver = requireValue(args, ++i, "--approver");
                } else if (arg.startsWith("--approver=")) {
                    approver = arg.substring("--approver=".length()).trim();
                } else {
                    throw new IllegalArgumentException("unsupported argument: " + arg + "\n" + usage());
                }
            }
            if (candidateId == null || rationale == null || approver == null) {
                throw new IllegalArgumentException("missing required decision arguments\n" + usage());
            }
            return new DecisionArgs(baseline, candidateId, rationale, approver);
        }
    }

    private record PromoteArgs(Path baselineDirectory, String candidateId) {
        static PromoteArgs parse(String[] args) {
            Path baseline = defaultBaseline();
            String candidateId = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--baseline".equals(arg)) {
                    baseline = Path.of(requireValue(args, ++i, "--baseline"));
                } else if (arg.startsWith("--baseline=")) {
                    baseline = Path.of(arg.substring("--baseline=".length()).trim());
                } else if ("--candidate-id".equals(arg)) {
                    candidateId = requireValue(args, ++i, "--candidate-id");
                } else if (arg.startsWith("--candidate-id=")) {
                    candidateId = arg.substring("--candidate-id=".length()).trim();
                } else {
                    throw new IllegalArgumentException("unsupported argument: " + arg + "\n" + usage());
                }
            }
            if (candidateId == null) {
                throw new IllegalArgumentException("missing --candidate-id\n" + usage());
            }
            return new PromoteArgs(baseline, candidateId);
        }
    }

    private record RollbackArgs(Path baselineDirectory, String historyId, String rationale, String approver) {
        static RollbackArgs parse(String[] args) {
            Path baseline = defaultBaseline();
            String historyId = null;
            String rationale = null;
            String approver = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--baseline".equals(arg)) {
                    baseline = Path.of(requireValue(args, ++i, "--baseline"));
                } else if (arg.startsWith("--baseline=")) {
                    baseline = Path.of(arg.substring("--baseline=".length()).trim());
                } else if ("--history-id".equals(arg)) {
                    historyId = requireValue(args, ++i, "--history-id");
                } else if (arg.startsWith("--history-id=")) {
                    historyId = arg.substring("--history-id=".length()).trim();
                } else if ("--rationale".equals(arg)) {
                    rationale = requireValue(args, ++i, "--rationale");
                } else if (arg.startsWith("--rationale=")) {
                    rationale = arg.substring("--rationale=".length()).trim();
                } else if ("--approver".equals(arg)) {
                    approver = requireValue(args, ++i, "--approver");
                } else if (arg.startsWith("--approver=")) {
                    approver = arg.substring("--approver=".length()).trim();
                } else {
                    throw new IllegalArgumentException("unsupported argument: " + arg + "\n" + usage());
                }
            }
            if (historyId == null || rationale == null || approver == null) {
                throw new IllegalArgumentException("missing required rollback arguments\n" + usage());
            }
            return new RollbackArgs(baseline, historyId, rationale, approver);
        }
    }

    private record ListHistoryArgs(Path baselineDirectory) {
        static ListHistoryArgs parse(String[] args) {
            Path baseline = defaultBaseline();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--baseline".equals(arg)) {
                    baseline = Path.of(requireValue(args, ++i, "--baseline"));
                } else if (arg.startsWith("--baseline=")) {
                    baseline = Path.of(arg.substring("--baseline=".length()).trim());
                } else {
                    throw new IllegalArgumentException("unsupported argument: " + arg + "\n" + usage());
                }
            }
            return new ListHistoryArgs(baseline);
        }
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[index].trim();
    }
}
