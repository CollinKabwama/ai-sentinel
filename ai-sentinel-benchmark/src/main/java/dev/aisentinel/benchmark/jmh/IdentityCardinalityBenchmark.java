package dev.aisentinel.benchmark.jmh;

import dev.aisentinel.benchmark.fixture.BenchmarkComponentFactory;
import dev.aisentinel.benchmark.fixture.BenchmarkFeatureFactory;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.StatisticalScorer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Statistical scorer latency across in-memory identity cardinality.
 * Measures local-map behavior only — not Redis or multi-JVM scale.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class IdentityCardinalityBenchmark {

    @Param({"1", "100", "1000", "10000"})
    public int identityCount;

    private StatisticalScorer scorer;
    private RequestFeatures[] features;
    private final AtomicInteger cursor = new AtomicInteger();

    @Setup(Level.Trial)
    public void setup() {
        scorer = BenchmarkComponentFactory.newStatisticalScorer();
        features = new RequestFeatures[identityCount];
        for (int i = 0; i < identityCount; i++) {
            String id = "id-" + i;
            String endpoint = "/api/benchmark";
            RequestFeatures baseline = BenchmarkFeatureFactory.establishedBaseline(id, endpoint);
            features[i] = baseline;
            for (int u = 0; u < 8; u++) {
                scorer.update(baseline);
            }
        }
        double probe = scorer.score(features[0]);
        if (Double.isNaN(probe) || Double.isInfinite(probe) || probe < 0.0) {
            throw new IllegalStateException("Cardinality probe score invalid: " + probe);
        }
    }

    @Benchmark
    public void scoreRotatingIdentities(Blackhole blackhole) {
        int idx = Math.floorMod(cursor.getAndIncrement(), identityCount);
        blackhole.consume(scorer.score(features[idx]));
    }
}
