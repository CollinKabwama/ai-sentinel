package dev.aisentinel.core.dataset.corpus;

import dev.aisentinel.core.contract.EvaluationEvent;

/**
 * One generated detector-facing event plus evaluation-layer ground-truth fields.
 * Ground-truth fields never appear on the serialized {@link EvaluationEvent}.
 */
record CompiledEvent(EvaluationEvent event, String expectedClass, String category) {
}
