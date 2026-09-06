package dev.aisentinel.benchmark.compare;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnitConverterTest {

    @Test
    void convertsNanosecondsAndMilliseconds() {
        assertThat(UnitConverter.convert(1_000_000.0, "ns/op", "ms/op")).isEqualTo(1.0);
        assertThat(UnitConverter.convert(1.0, "ms/op", "ns/op")).isEqualTo(1_000_000.0);
    }

    @Test
    void rejectsIncompatibleUnits() {
        assertThat(UnitConverter.convert(1.0, "ops/s", "ns/op")).isNull();
    }
}
