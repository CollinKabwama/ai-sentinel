package dev.aisentinel.benchmark.deployment;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class RemoteFaultController {

    enum Mode {
        PASS_THROUGH,
        DELAY,
        MALFORMED_RESPONSE
    }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.PASS_THROUGH);
    private final AtomicLong delayMillis = new AtomicLong();

    Mode mode() {
        return mode.get();
    }

    long delayMillis() {
        return delayMillis.get();
    }

    void reset() {
        mode.set(Mode.PASS_THROUGH);
        delayMillis.set(0L);
    }

    void delay(long millis) {
        mode.set(Mode.DELAY);
        delayMillis.set(Math.max(0L, millis));
    }

    void malformedResponse() {
        mode.set(Mode.MALFORMED_RESPONSE);
        delayMillis.set(0L);
    }
}
