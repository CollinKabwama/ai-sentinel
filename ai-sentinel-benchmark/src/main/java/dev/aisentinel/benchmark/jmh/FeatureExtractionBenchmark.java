package dev.aisentinel.benchmark.jmh;

import dev.aisentinel.benchmark.fixture.BenchmarkHttpRequestView;
import dev.aisentinel.core.feature.DefaultFeatureExtractor;
import dev.aisentinel.core.http.HttpRequestView;
import dev.aisentinel.core.model.RequestContext;
import dev.aisentinel.core.model.RequestFeatures;
import dev.aisentinel.core.store.BaselineStore;
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

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Feature-extraction cost independent of scoring/policy.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class FeatureExtractionBenchmark {

    @Param({"typical", "small", "largerValid"})
    public String requestShape;

    private DefaultFeatureExtractor extractor;
    private HttpRequestView request;
    private String identity;

    @Setup(Level.Trial)
    public void setup() {
        identity = "feature-id";
        BaselineStore store = new BaselineStore(Duration.ofMinutes(5), 100_000);
        extractor = new DefaultFeatureExtractor(store, 100_000, 300_000L);
        request = switch (requestShape) {
            case "typical" -> BenchmarkHttpRequestView.typical();
            case "small" -> BenchmarkHttpRequestView.small();
            case "largerValid" -> BenchmarkHttpRequestView.largerValid();
            default -> throw new IllegalArgumentException("Unknown requestShape: " + requestShape);
        };
        RequestFeatures probe = extractor.extract(request, identity, new RequestContext());
        if (probe == null || probe.identityHash() == null) {
            throw new IllegalStateException("Feature extraction probe failed");
        }
    }

    @Benchmark
    public void extract(Blackhole blackhole) {
        blackhole.consume(extractor.extract(request, identity, new RequestContext()));
    }
}
