package io.aisentinel.autoconfigure.web;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityHasherTest {

    @Test
    void sha256Hex_matchesKnownVectorForEmptyString() {
        assertThat(IdentityHasher.sha256Hex(""))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void sha256Hex_isStableAcrossRepeatedCalls() {
        String first = IdentityHasher.sha256Hex("same-input");
        String second = IdentityHasher.sha256Hex("same-input");
        assertThat(second).isEqualTo(first);
    }

    @Test
    void sha256Hex_concurrentCallsMatchSequential() throws Exception {
        String input = "concurrent-identity";
        String expected = IdentityHasher.sha256Hex(input);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                tasks.add(() -> IdentityHasher.sha256Hex(input));
            }
            for (Future<String> f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isEqualTo(expected);
            }
        } finally {
            pool.shutdown();
        }
    }
}
