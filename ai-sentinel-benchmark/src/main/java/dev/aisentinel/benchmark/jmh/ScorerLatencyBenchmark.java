package dev.aisentinel.benchmark.jmh;

import dev.aisentinel.benchmark.fixture.BenchmarkComponentFactory;
import dev.aisentinel.benchmark.fixture.BenchmarkFeatureFactory;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.scoring.AnomalyScorer;
import dev.aisentinel.core.scoring.CompositeScorer;
import dev.aisentinel.core.scoring.IsolationForestScorer;
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

/**
 * Scorer-only latency for statistical, Isolation Forest, and composite implementations.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class ScorerLatencyBenchmark {

    @Param({"statistical", "isolationForestModel", "isolationForestFallback", "compositeStatisticalOnly", "compositeWithIf"})
    public String scorerKind;

    private AnomalyScorer scorer;
    private RequestFeatures features;

    @Setup(Level.Trial)
    public void setup() {
        features = BenchmarkFeatureFactory.establishedBaseline("bench-id", "/api/benchmark");
        scorer = switch (scorerKind) {
            case "statistical" -> {
                StatisticalScorer statistical = BenchmarkComponentFactory.newStatisticalScorer();
                BenchmarkComponentFactory.seedEstablishedBaseline(statistical, "bench-id", "/api/benchmark", 64);
                yield statistical;
            }
            case "isolationForestModel" -> BenchmarkComponentFactory.newIsolationForestWithModel();
            case "isolationForestFallback" -> BenchmarkComponentFactory.newIsolationForestWithoutModel();
            case "compositeStatisticalOnly" -> {
                CompositeScorer composite = BenchmarkComponentFactory.newStatisticalOnlyComposite();
                StatisticalScorer statistical = (StatisticalScorer) composite.scorersView().get(0);
                BenchmarkComponentFactory.seedEstablishedBaseline(statistical, "bench-id", "/api/benchmark", 64);
                yield composite;
            }
            case "compositeWithIf" -> {
                CompositeScorer composite = BenchmarkComponentFactory.newCompositeWithIsolationForestModel();
                StatisticalScorer statistical = (StatisticalScorer) composite.scorersView().get(0);
                BenchmarkComponentFactory.seedEstablishedBaseline(statistical, "bench-id", "/api/benchmark", 64);
                IsolationForestScorer ifScorer = (IsolationForestScorer) composite.scorersView().get(1);
                if (!ifScorer.isModelLoaded()) {
                    throw new IllegalStateException("compositeWithIf requires a loaded Isolation Forest model");
                }
                yield composite;
            }
            default -> throw new IllegalArgumentException("Unknown scorerKind: " + scorerKind);
        };
        double probe = scorer.score(features);
        if ("isolationForestFallback".equals(scorerKind)) {
            if (Double.isNaN(probe) || Double.isInfinite(probe)) {
                throw new IllegalStateException("Fallback IF score should be finite");
            }
        } else if (Double.isNaN(probe) || Double.isInfinite(probe) || probe < 0.0) {
            throw new IllegalStateException("Unexpected scorer probe result: " + probe);
        }
    }

    @Benchmark
    public void score(Blackhole blackhole) {
        blackhole.consume(scorer.score(features));
    }
}
