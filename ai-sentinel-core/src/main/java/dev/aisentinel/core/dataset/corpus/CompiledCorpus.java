package dev.aisentinel.core.dataset.corpus;

import java.util.List;
import java.util.Objects;

/**
 * Ordered compiled events ready for artifact writing.
 */
record CompiledCorpus(List<CompiledEvent> events) {
    CompiledCorpus {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (events.isEmpty()) {
            throw new CorpusGeneratorException("Compiled corpus produced zero events");
        }
    }
}
