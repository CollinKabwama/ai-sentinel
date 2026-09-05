package dev.aisentinel.benchmark.jmh;

import dev.aisentinel.benchmark.fixture.BenchmarkHttpRequestView;
import dev.aisentinel.benchmark.fixture.BenchmarkPipelineFactory;
import dev.aisentinel.benchmark.fixture.NoopEnforcementResponse;
import dev.aisentinel.core.SentinelPipeline;
import dev.aisentinel.core.http.HttpRequestView;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * In-process pipeline cost (identity resolve → features → decision → enforcement apply).
 * Separate methods encode concurrency so JMH thread counts match measured work.
 */
@Fork(1)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class PipelineBenchmark {

    private SentinelPipeline pipeline;
    private HttpRequestView request;
    private String identity;

    @Setup(Level.Trial)
    public void setup() {
        identity = "pipeline-id";
        pipeline = BenchmarkPipelineFactory.statisticalPipeline().pipeline();
        request = BenchmarkHttpRequestView.typical();
        for (int i = 0; i < 64; i++) {
            pipeline.process(request, NoopEnforcementResponse.INSTANCE, identity);
        }
        if (!pipeline.process(request, NoopEnforcementResponse.INSTANCE, identity)) {
            throw new IllegalStateException("Pipeline probe unexpectedly blocked");
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SampleTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Threads(1)
    public void processLatencyOneThread(Blackhole blackhole) {
        blackhole.consume(pipeline.process(request, NoopEnforcementResponse.INSTANCE, identity));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Threads(1)
    public void processThroughputOneThread(Blackhole blackhole) {
        blackhole.consume(pipeline.process(request, NoopEnforcementResponse.INSTANCE, identity));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Threads(4)
    public void processThroughputFourThreads(Blackhole blackhole) {
        blackhole.consume(pipeline.process(request, NoopEnforcementResponse.INSTANCE, identity));
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Threads(16)
    public void processThroughputSixteenThreads(Blackhole blackhole) {
        blackhole.consume(pipeline.process(request, NoopEnforcementResponse.INSTANCE, identity));
    }
}
