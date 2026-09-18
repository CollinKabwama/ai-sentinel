package dev.aisentinel.core.scoring.lifecycle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit champion/challenger governance, approval, promotion, and rollback.
 * <p>
 * Process-local synchronized state with optional filesystem persistence.
 * Does not rewire production scorers, policy, or enforcement.
 * <p>
 * {@code APPROVED != PROMOTED}<br>
 * {@code PROMOTED != PRODUCTION DEPLOYED}<br>
 * {@code ROLLBACK != PRODUCTION DEPLOYMENT ROLLBACK}<br>
 * {@code METRIC IMPROVEMENT != AUTOMATIC PROMOTION}
 */
public final class ModelLifecycleManager {

    private final Object lock = new Object();
    private final Path governanceRoot;

    private ModelLifecycleIdentity champion;
    private ChallengerDesignation challengerDesignation;
    private ChampionChallengerComparison boundComparison;
    private ModelPromotionDecision pendingDecision;
    private final List<ModelLifecycleHistoryEntry> history = new ArrayList<>();
    private long nextSequence;

    /**
     * In-memory only. State is lost on restart unless the caller persists elsewhere.
     */
    public ModelLifecycleManager() {
        this.governanceRoot = null;
    }

    /**
     * Optional local filesystem governance root. Create-new history files; current
     * designation files are replaced intentionally. Not distributed consensus.
     * Not crash-transactional across all files.
     * <p>
     * When the root already contains published state, designations, decisions, and
     * history are reconstructed. A pending approval still requires re-binding the
     * identical comparison evidence before {@link #promote} (restart attestation).
     */
    public ModelLifecycleManager(Path governanceRoot) {
        this.governanceRoot = Objects.requireNonNull(governanceRoot, "governanceRoot");
        try {
            ModelLifecycleStateLoader.LoadedState loaded = ModelLifecycleStateLoader.load(governanceRoot);
            this.champion = loaded.champion();
            this.challengerDesignation = loaded.challenger();
            this.boundComparison = null; // full comparison must be re-bound after restart
            this.pendingDecision = loaded.decision();
            this.history.addAll(loaded.history());
            this.nextSequence = loaded.nextSequence();
        } catch (IOException e) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.PUBLICATION_FAILURE,
                "failed to load governance state: " + e.getMessage(),
                e
            );
        }
    }

    public Optional<Path> governanceRoot() {
        return Optional.ofNullable(governanceRoot);
    }

    public Optional<ModelLifecycleIdentity> currentChampion() {
        synchronized (lock) {
            return Optional.ofNullable(champion);
        }
    }

    public Optional<ChallengerDesignation> currentChallenger() {
        synchronized (lock) {
            return Optional.ofNullable(challengerDesignation);
        }
    }

    public Optional<ModelPromotionDecision> pendingDecision() {
        synchronized (lock) {
            return Optional.ofNullable(pendingDecision);
        }
    }

    public List<ModelLifecycleHistoryEntry> history() {
        synchronized (lock) {
            return List.copyOf(history);
        }
    }

    /**
     * Explicitly designates the initial lifecycle champion.
     * Does not wire production scorers.
     */
    public ModelLifecycleOperationResult designateChampion(ModelLifecycleIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        synchronized (lock) {
            if (champion != null) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.PRECONDITION_FAILED,
                    "champion already designated; use promote/rollback to change"
                );
            }
            champion = identity;
            persistChampionUnlocked();
            return new ModelLifecycleOperationResult(
                "CHAMPION_DESIGNATED",
                champion,
                null,
                ModelPromotionDecisionStatus.NOT_DECIDED,
                evidenceHash(identity.bindingKey()),
                "lifecycle champion designated; production scorer wiring unchanged"
            );
        }
    }

    /**
     * Explicit challenger designation. Acceptance and shadow enablement do not
     * create a challenger automatically.
     */
    public ModelLifecycleOperationResult designateChallenger(ChallengerDesignation designation) {
        Objects.requireNonNull(designation, "designation");
        synchronized (lock) {
            requireChampionUnlocked();
            if (!champion.matches(designation.expectedChampion())) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.STALE_CHAMPION,
                    "expected champion does not match current lifecycle champion"
                );
            }
            if (challengerDesignation != null) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.CHALLENGER_ALREADY_DESIGNATED,
                    "challenger already designated; clear decision path or promote/reject first"
                );
            }
            if (champion.matches(designation.challenger())) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.IDENTITY_MISMATCH,
                    "challenger must differ from champion"
                );
            }
            challengerDesignation = designation;
            boundComparison = null;
            pendingDecision = null;
            persistChallengerUnlocked();
            clearDecisionFilesUnlocked();
            return new ModelLifecycleOperationResult(
                "CHALLENGER_DESIGNATED",
                champion,
                designation.challenger(),
                ModelPromotionDecisionStatus.NOT_DECIDED,
                evidenceHash(designation.challenger().bindingKey()),
                "challenger designated; not approved and not promoted"
            );
        }
    }

    /**
     * Clears the current challenger designation and any pending decision without
     * changing the lifecycle champion. Explicit only — not automatic.
     */
    public ModelLifecycleOperationResult clearChallengerDesignation() {
        synchronized (lock) {
            requireChampionUnlocked();
            if (challengerDesignation == null && pendingDecision == null && boundComparison == null) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.CHALLENGER_NOT_DESIGNATED,
                    "no challenger designation to clear"
                );
            }
            challengerDesignation = null;
            boundComparison = null;
            pendingDecision = null;
            clearChallengerFilesUnlocked();
            return new ModelLifecycleOperationResult(
                "CHALLENGER_CLEARED",
                champion,
                null,
                ModelPromotionDecisionStatus.NOT_DECIDED,
                evidenceHash(champion.bindingKey()),
                "challenger designation cleared; champion unchanged"
            );
        }
    }

    /**
     * Binds objective comparison evidence to the current challenger without deciding.
     * <p>
     * After a process restart with a persisted approval, re-bind the identical
     * comparison (same {@code comparisonSha256Hex}) to re-attest evidence before
     * promote. A different comparison hash is rejected while a decision is pending.
     */
    public ModelLifecycleOperationResult bindComparison(ChampionChallengerComparison comparison) {
        Objects.requireNonNull(comparison, "comparison");
        synchronized (lock) {
            requireChampionUnlocked();
            requireChallengerUnlocked();
            assertComparisonIdentitiesUnlocked(comparison);
            if (pendingDecision != null) {
                if (!comparison.comparisonSha256Hex().equals(pendingDecision.comparisonSha256Hex())) {
                    throw new ModelLifecycleException(
                        ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                        "cannot substitute comparison evidence under an existing decision"
                    );
                }
                boundComparison = comparison;
                persistComparisonUnlocked();
                return new ModelLifecycleOperationResult(
                    "COMPARISON_REBOUND",
                    champion,
                    challengerDesignation.challenger(),
                    pendingDecision.status(),
                    comparison.comparisonSha256Hex(),
                    "identical comparison re-bound after restart; decision unchanged"
                );
            }
            boundComparison = comparison;
            persistComparisonUnlocked();
            return new ModelLifecycleOperationResult(
                "COMPARISON_BOUND",
                champion,
                challengerDesignation.challenger(),
                ModelPromotionDecisionStatus.NOT_DECIDED,
                comparison.comparisonSha256Hex(),
                "comparison bound; METRIC DELTA != GOVERNANCE DECISION"
            );
        }
    }

    public PromotionEligibilityAssessment assessEligibility(
        PromotionEligibilityPolicy policy,
        ChampionChallengerComparison comparison
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(comparison, "comparison");
        synchronized (lock) {
            requireChampionUnlocked();
            requireChallengerUnlocked();
            assertComparisonIdentitiesUnlocked(comparison);
            return policy.assess(comparison);
        }
    }

    /**
     * Explicit approval. Does not change the lifecycle champion.
     * {@code APPROVED != PROMOTED}
     */
    public ModelLifecycleOperationResult approvePromotion(
        ChampionChallengerComparison comparison,
        PromotionEligibilityPolicy policy,
        String rationale,
        String approver
    ) {
        return decide(comparison, policy, rationale, approver, true);
    }

    /**
     * Explicit rejection. Does not change the lifecycle champion.
     */
    public ModelLifecycleOperationResult rejectPromotion(
        ChampionChallengerComparison comparison,
        String rationale,
        String approver
    ) {
        return decide(comparison, PromotionEligibilityPolicy.defaults(), rationale, approver, false);
    }

    private ModelLifecycleOperationResult decide(
        ChampionChallengerComparison comparison,
        PromotionEligibilityPolicy policy,
        String rationale,
        String approver,
        boolean approve
    ) {
        Objects.requireNonNull(comparison, "comparison");
        Objects.requireNonNull(policy, "policy");
        synchronized (lock) {
            requireChampionUnlocked();
            requireChallengerUnlocked();
            assertComparisonIdentitiesUnlocked(comparison);
            if (pendingDecision != null) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.ALREADY_DECIDED,
                    "governance decision already recorded for current challenger"
                );
            }
            if (approve) {
                PromotionEligibilityAssessment eligibility = policy.assess(comparison);
                if (!eligibility.eligible()) {
                    throw new ModelLifecycleException(
                        ModelLifecycleException.Code.NOT_ELIGIBLE,
                        "challenger is not eligible: " + eligibility.issues().get(0).detail()
                    );
                }
            }
            ModelPromotionDecision decision = approve
                ? ModelPromotionDecision.approved(
                    champion, challengerDesignation.challenger(),
                    comparison.comparisonSha256Hex(), rationale, approver)
                : ModelPromotionDecision.rejected(
                    champion, challengerDesignation.challenger(),
                    comparison.comparisonSha256Hex(), rationale, approver);
            boundComparison = comparison;
            pendingDecision = decision;
            persistComparisonUnlocked();
            persistDecisionUnlocked();
            return new ModelLifecycleOperationResult(
                approve ? "PROMOTION_APPROVED" : "PROMOTION_REJECTED",
                champion,
                challengerDesignation.challenger(),
                decision.status(),
                decision.decisionSha256Hex(),
                approve
                    ? "approved; champion unchanged until explicit promote"
                    : "rejected; champion unchanged"
            );
        }
    }

    /**
     * Explicit promotion of an approved challenger to lifecycle champion.
     * Does not rewire production scorers.
     * {@code PROMOTED != PRODUCTION DEPLOYED}
     */
    public ModelLifecycleOperationResult promote(ModelLifecycleIdentity expectedChampion) {
        Objects.requireNonNull(expectedChampion, "expectedChampion");
        synchronized (lock) {
            requireChampionUnlocked();
            requireChallengerUnlocked();
            if (!champion.matches(expectedChampion)) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.STALE_CHAMPION,
                    "expected champion does not match current lifecycle champion"
                );
            }
            if (pendingDecision == null) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.APPROVAL_REQUIRED,
                    "explicit approval required before promotion"
                );
            }
            if (pendingDecision.status() == ModelPromotionDecisionStatus.REJECTED) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.REJECTED_NOT_PROMOTABLE,
                    "rejected challenger cannot be promoted"
                );
            }
            if (pendingDecision.status() != ModelPromotionDecisionStatus.APPROVED) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.APPROVAL_REQUIRED,
                    "challenger must be APPROVED before promotion"
                );
            }
            if (!pendingDecision.championAtDecision().matches(champion)) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.STALE_APPROVAL,
                    "approval champion context no longer matches current champion"
                );
            }
            if (!pendingDecision.challenger().matches(challengerDesignation.challenger())) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.IDENTITY_MISMATCH,
                    "approval challenger does not match designated challenger"
                );
            }
            if (boundComparison == null
                || !boundComparison.comparisonSha256Hex().equals(pendingDecision.comparisonSha256Hex())) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                    "bound comparison does not match approval evidence"
                );
            }
            if (!boundComparison.champion().matches(champion)
                || !boundComparison.challenger().matches(challengerDesignation.challenger())) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.EVIDENCE_MISMATCH,
                    "comparison identities do not match current designations"
                );
            }

            ModelLifecycleIdentity previous = champion;
            ModelLifecycleIdentity promoted = challengerDesignation.challenger();
            ModelPromotionRecord record = new ModelPromotionRecord(
                previous,
                promoted,
                pendingDecision.decisionSha256Hex(),
                pendingDecision.comparisonSha256Hex(),
                pendingDecision.rationale(),
                pendingDecision.approver()
            );
            for (ModelLifecycleHistoryEntry existing : history) {
                if (existing.kind() == ModelLifecycleHistoryEntry.Kind.PROMOTION
                    && existing.entryId().equals(record.promotionId())) {
                    throw new ModelLifecycleException(
                        ModelLifecycleException.Code.ALREADY_PROMOTED,
                        "identical promotion already recorded"
                    );
                }
            }

            ModelLifecycleHistoryEntry entry =
                ModelLifecycleHistoryEntry.promotion(record, nextSequence++);
            try {
                persistHistoryEntryUnlocked(entry);
            } catch (IOException e) {
                nextSequence--;
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.PUBLICATION_FAILURE,
                    "failed to persist promotion history: " + e.getMessage(),
                    e
                );
            }
            history.add(entry);
            champion = promoted;
            challengerDesignation = null;
            boundComparison = null;
            pendingDecision = null;
            persistChampionUnlocked();
            clearChallengerFilesUnlocked();
            return new ModelLifecycleOperationResult(
                "PROMOTED",
                champion,
                null,
                ModelPromotionDecisionStatus.APPROVED,
                record.promotionSha256Hex(),
                "lifecycle champion updated; production scorer wiring unchanged"
            );
        }
    }

    /**
     * Explicit rollback of a prior promotion. Restores previous lifecycle champion.
     * Does not rewire production scorers.
     * {@code ROLLBACK != PRODUCTION DEPLOYMENT ROLLBACK}
     */
    public ModelLifecycleOperationResult rollback(
        String promotionId,
        ModelLifecycleIdentity expectedCurrentChampion,
        String rationale,
        String approver
    ) {
        Objects.requireNonNull(promotionId, "promotionId");
        Objects.requireNonNull(expectedCurrentChampion, "expectedCurrentChampion");
        synchronized (lock) {
            requireChampionUnlocked();
            if (!champion.matches(expectedCurrentChampion)) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.STALE_ROLLBACK,
                    "expected current champion does not match lifecycle champion"
                );
            }
            ModelPromotionRecord target = findPromotionUnlocked(promotionId);
            if (!target.newChampion().matches(champion)) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.STALE_ROLLBACK,
                    "promotion new-champion does not match current champion"
                );
            }
            if (target.previousChampion().matches(champion)) {
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.NO_OP_IDENTICAL,
                    "rollback target is identical to current champion"
                );
            }

            ModelRollbackRecord record = new ModelRollbackRecord(
                promotionId,
                champion,
                target.previousChampion(),
                rationale,
                approver
            );
            ModelLifecycleHistoryEntry entry =
                ModelLifecycleHistoryEntry.rollback(record, nextSequence++);
            try {
                persistHistoryEntryUnlocked(entry);
            } catch (IOException e) {
                nextSequence--;
                throw new ModelLifecycleException(
                    ModelLifecycleException.Code.PUBLICATION_FAILURE,
                    "failed to persist rollback history: " + e.getMessage(),
                    e
                );
            }
            history.add(entry);
            champion = target.previousChampion();
            challengerDesignation = null;
            boundComparison = null;
            pendingDecision = null;
            persistChampionUnlocked();
            clearChallengerFilesUnlocked();
            return new ModelLifecycleOperationResult(
                "ROLLED_BACK",
                champion,
                null,
                ModelPromotionDecisionStatus.NOT_DECIDED,
                record.rollbackSha256Hex(),
                "lifecycle champion restored; production scorer wiring unchanged"
            );
        }
    }

    private ModelPromotionRecord findPromotionUnlocked(String promotionId) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ModelLifecycleHistoryEntry entry = history.get(i);
            if (entry.kind() == ModelLifecycleHistoryEntry.Kind.PROMOTION
                && entry.entryId().equals(promotionId)) {
                return entry.promotion().orElseThrow();
            }
        }
        throw new ModelLifecycleException(
            ModelLifecycleException.Code.HISTORY_NOT_FOUND,
            "promotion not found: " + promotionId
        );
    }

    private void requireChampionUnlocked() {
        if (champion == null) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.CHAMPION_REQUIRED,
                "lifecycle champion must be designated first"
            );
        }
    }

    private void requireChallengerUnlocked() {
        if (challengerDesignation == null) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.CHALLENGER_REQUIRED,
                "challenger must be explicitly designated"
            );
        }
    }

    private void assertComparisonIdentitiesUnlocked(ChampionChallengerComparison comparison) {
        if (!comparison.champion().matches(champion)) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.IDENTITY_MISMATCH,
                "comparison champion does not match current champion"
            );
        }
        if (!comparison.challenger().matches(challengerDesignation.challenger())) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.IDENTITY_MISMATCH,
                "comparison challenger does not match designated challenger"
            );
        }
        if (comparison.challengerEvidence().evaluationJsonSha256Hex().isPresent()
            && comparison.championEvidence().evaluationJsonSha256Hex().isPresent()
            && comparison.challengerEvidence().evaluationJsonSha256Hex().orElseThrow()
                .equals(comparison.championEvidence().evaluationJsonSha256Hex().orElseThrow())
            && !comparison.champion().matches(comparison.challenger())) {
            // same evidence hash for different identities is suspicious but allowed only if
            // digests intentionally collide; no action — substitution is blocked by identity match above
        }
    }

    private void persistChampionUnlocked() {
        if (governanceRoot == null || champion == null) {
            return;
        }
        try {
            Files.createDirectories(governanceRoot);
            Path path = governanceRoot.resolve(ModelLifecycleSchemas.CHAMPION_FILE);
            byte[] bytes = ModelLifecycleEvidenceFormats.championJson(champion)
                .getBytes(StandardCharsets.UTF_8);
            writeReplace(path, bytes);
        } catch (IOException e) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.PUBLICATION_FAILURE,
                "failed to persist champion: " + e.getMessage(),
                e
            );
        }
    }

    private void persistChallengerUnlocked() {
        if (governanceRoot == null || challengerDesignation == null) {
            return;
        }
        try {
            Files.createDirectories(governanceRoot);
            Path path = governanceRoot.resolve(ModelLifecycleSchemas.CHALLENGER_FILE);
            byte[] bytes = ModelLifecycleEvidenceFormats.challengerJson(challengerDesignation)
                .getBytes(StandardCharsets.UTF_8);
            writeReplace(path, bytes);
        } catch (IOException e) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.PUBLICATION_FAILURE,
                "failed to persist challenger: " + e.getMessage(),
                e
            );
        }
    }

    private void persistComparisonUnlocked() {
        if (governanceRoot == null || boundComparison == null) {
            return;
        }
        try {
            Files.createDirectories(governanceRoot);
            Path path = governanceRoot.resolve(ModelLifecycleSchemas.COMPARISON_BINDING_FILE);
            byte[] bytes = ModelLifecycleEvidenceFormats.comparisonBindingJson(boundComparison)
                .getBytes(StandardCharsets.UTF_8);
            writeReplace(path, bytes);
        } catch (IOException e) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.PUBLICATION_FAILURE,
                "failed to persist comparison binding: " + e.getMessage(),
                e
            );
        }
    }

    private void persistDecisionUnlocked() {
        if (governanceRoot == null || pendingDecision == null) {
            return;
        }
        try {
            Files.createDirectories(governanceRoot);
            Path path = governanceRoot.resolve(ModelLifecycleSchemas.DECISION_FILE);
            byte[] bytes = ModelLifecycleEvidenceFormats.decisionJson(pendingDecision)
                .getBytes(StandardCharsets.UTF_8);
            writeNewOrReplaceDecision(path, bytes);
        } catch (IOException e) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.PUBLICATION_FAILURE,
                "failed to persist decision: " + e.getMessage(),
                e
            );
        }
    }

    private void persistHistoryEntryUnlocked(ModelLifecycleHistoryEntry entry) throws IOException {
        if (governanceRoot == null) {
            return;
        }
        Path historyDir = governanceRoot.resolve(ModelLifecycleSchemas.HISTORY_DIRECTORY);
        Files.createDirectories(historyDir);
        Path path = historyDir.resolve(ModelLifecycleEvidenceFormats.historyFileName(entry));
        String json = entry.kind() == ModelLifecycleHistoryEntry.Kind.PROMOTION
            ? ModelLifecycleEvidenceFormats.promotionJson(entry.promotion().orElseThrow())
            : ModelLifecycleEvidenceFormats.rollbackJson(entry.rollback().orElseThrow());
        writeNewFile(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private void clearChallengerFilesUnlocked() {
        if (governanceRoot == null) {
            return;
        }
        try {
            Files.deleteIfExists(governanceRoot.resolve(ModelLifecycleSchemas.CHALLENGER_FILE));
            Files.deleteIfExists(governanceRoot.resolve(ModelLifecycleSchemas.COMPARISON_BINDING_FILE));
            Files.deleteIfExists(governanceRoot.resolve(ModelLifecycleSchemas.DECISION_FILE));
        } catch (IOException e) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.PUBLICATION_FAILURE,
                "failed to clear challenger files: " + e.getMessage(),
                e
            );
        }
    }

    private void clearDecisionFilesUnlocked() {
        clearDecisionFileUnlocked();
        if (governanceRoot == null) {
            return;
        }
        try {
            Files.deleteIfExists(governanceRoot.resolve(ModelLifecycleSchemas.COMPARISON_BINDING_FILE));
        } catch (IOException e) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.PUBLICATION_FAILURE,
                "failed to clear comparison binding: " + e.getMessage(),
                e
            );
        }
    }

    private void clearDecisionFileUnlocked() {
        if (governanceRoot == null) {
            return;
        }
        try {
            Files.deleteIfExists(governanceRoot.resolve(ModelLifecycleSchemas.DECISION_FILE));
        } catch (IOException e) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.PUBLICATION_FAILURE,
                "failed to clear decision: " + e.getMessage(),
                e
            );
        }
    }

    private static void writeNewFile(Path path, byte[] bytes) throws IOException {
        try {
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            throw new ModelLifecycleException(
                ModelLifecycleException.Code.HISTORY_CONFLICT,
                "history file already exists: " + path.getFileName(),
                e
            );
        }
    }

    private static void writeReplace(Path path, byte[] bytes) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        try {
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeNewOrReplaceDecision(Path path, byte[] bytes) throws IOException {
        // Decision is current mutable governance pointer; history remains CREATE_NEW.
        writeReplace(path, bytes);
    }

    private static String evidenceHash(String material) {
        return ModelLifecycleCanonical.sha256Hex(material);
    }
}
