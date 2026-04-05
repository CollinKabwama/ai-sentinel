package io.aisentinel.core.scoring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsolationForestModelCodecTest {

    @Test
    void roundTripPreservesScore() throws Exception {
        List<double[]> samples = List.of(
            new double[] {1, 2, 3, 4, 5},
            new double[] {1.1, 2.1, 3.1, 4.1, 5.1},
            new double[] {5, 4, 3, 2, 1}
        );
        IsolationForestTrainer tr = new IsolationForestTrainer(5, 4, 99L);
        IsolationForestModel m = tr.train(samples);
        assertThat(m).isNotNull();
        byte[] bytes = IsolationForestModelCodec.encode(m);
        IsolationForestModel back = IsolationForestModelCodec.decode(bytes);
        double[] x = {1.2, 2.2, 3.2, 4.2, 5.2};
        assertThat(back.score(x)).isEqualTo(m.score(x));
    }

    @Test
    void rejectsBadMagic() {
        assertThatThrownBy(() -> IsolationForestModelCodec.decode(new byte[] {0, 0, 0, 0}))
            .isInstanceOf(Exception.class);
    }
}
