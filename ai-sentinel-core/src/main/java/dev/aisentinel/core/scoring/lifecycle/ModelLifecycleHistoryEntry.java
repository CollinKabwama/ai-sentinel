package dev.aisentinel.core.scoring.lifecycle;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable history entry for promotion or rollback. Never overwritten in place.
 */
public final class ModelLifecycleHistoryEntry {

    public enum Kind {
        PROMOTION,
        ROLLBACK
    }

    private final Kind kind;
    private final String entryId;
    private final ModelPromotionRecord promotion;
    private final ModelRollbackRecord rollback;
    private final long sequence;

    private ModelLifecycleHistoryEntry(
        Kind kind,
        String entryId,
        ModelPromotionRecord promotion,
        ModelRollbackRecord rollback,
        long sequence
    ) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.entryId = Objects.requireNonNull(entryId, "entryId");
        this.promotion = promotion;
        this.rollback = rollback;
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be >= 0");
        }
        this.sequence = sequence;
        if (kind == Kind.PROMOTION && (promotion == null || rollback != null)) {
            throw new IllegalArgumentException("PROMOTION history requires promotion record only");
        }
        if (kind == Kind.ROLLBACK && (rollback == null || promotion != null)) {
            throw new IllegalArgumentException("ROLLBACK history requires rollback record only");
        }
    }

    public static ModelLifecycleHistoryEntry promotion(ModelPromotionRecord record, long sequence) {
        Objects.requireNonNull(record, "record");
        return new ModelLifecycleHistoryEntry(
            Kind.PROMOTION, record.promotionId(), record, null, sequence);
    }

    public static ModelLifecycleHistoryEntry rollback(ModelRollbackRecord record, long sequence) {
        Objects.requireNonNull(record, "record");
        return new ModelLifecycleHistoryEntry(
            Kind.ROLLBACK, record.rollbackId(), null, record, sequence);
    }

    public Kind kind() {
        return kind;
    }

    public String entryId() {
        return entryId;
    }

    public Optional<ModelPromotionRecord> promotion() {
        return Optional.ofNullable(promotion);
    }

    public Optional<ModelRollbackRecord> rollback() {
        return Optional.ofNullable(rollback);
    }

    public long sequence() {
        return sequence;
    }
}
