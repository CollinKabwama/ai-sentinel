package io.aisentinel.autoconfigure.distributed.training;

import io.aisentinel.autoconfigure.config.SentinelProperties;
import io.aisentinel.core.enforcement.EnforcementScope;
import io.aisentinel.core.metrics.SentinelMetrics;
import io.aisentinel.core.model.RequestFeatures;
import io.aisentinel.core.policy.EnforcementAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.aisentinel.distributed.training.TrainingCandidatePublishRequest;
import io.aisentinel.distributed.training.TrainingFingerprintHashes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTrainingCandidatePublisherTest {

    private AsyncTrainingCandidatePublisher publisher;

    @AfterEach
    void tearDown() {
        if (publisher != null) {
            publisher.destroy();
        }
    }

    @Test
    void noopWhenDisabled() {
        SentinelProperties props = props(false, 1.0, 256, 0.0, true, 0.7);
        CapturingTransport transport = new CapturingTransport();
        publisher = new AsyncTrainingCandidatePublisher(props, SentinelMetrics.NOOP, new TrainingPublishStatus(), transport);
        publisher.publish(request(0.5, 0.1, 0.2));
        assertThat(transport.records).isEmpty();
    }

    @Test
    void publishesWhenGatesPass() throws Exception {
        SentinelProperties props = props(true, 1.0, 8, 0.0, false, 0.7);
        CapturingTransport transport = new CapturingTransport();
        publisher = new AsyncTrainingCandidatePublisher(props, SentinelMetrics.NOOP, new TrainingPublishStatus(), transport);
        publisher.publish(request(0.9, null, null));
        Thread.sleep(300);
        assertThat(transport.records).hasSize(1);
        assertThat(transport.records.get(0).compositeScore()).isEqualTo(0.9);
    }

    @Test
    void skipsGateWhenCompositeBelowMin() {
        SentinelProperties props = props(true, 1.0, 256, 0.8, false, 0.7);
        AtomicLong skipped = new AtomicLong();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordTrainingCandidatePublishSkippedGate() {
                skipped.incrementAndGet();
            }
        };
        CapturingTransport transport = new CapturingTransport();
        publisher = new AsyncTrainingCandidatePublisher(props, metrics, new TrainingPublishStatus(), transport);
        publisher.publish(request(0.5, null, null));
        assertThat(skipped.get()).isEqualTo(1);
        assertThat(transport.records).isEmpty();
    }

    @Test
    void skipsAntiPoisonWhenIfScoreHigh() {
        SentinelProperties props = props(true, 1.0, 256, 0.0, true, 0.5);
        props.getIsolationForest().setTrainingRejectionScoreThreshold(0.5);
        AtomicLong skipped = new AtomicLong();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordTrainingCandidatePublishSkippedGate() {
                skipped.incrementAndGet();
            }
        };
        CapturingTransport transport = new CapturingTransport();
        publisher = new AsyncTrainingCandidatePublisher(props, metrics, new TrainingPublishStatus(), transport);
        publisher.publish(request(0.9, 0.1, 0.9));
        assertThat(skipped.get()).isEqualTo(1);
        assertThat(transport.records).isEmpty();
    }

    @Test
    void dropsWhenInFlightSaturated() throws Exception {
        SentinelProperties props = props(true, 1.0, 1, 0.0, false, 0.7);
        CountDownLatch block = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        TrainingCandidateTransport slow = record -> {
            attempts.incrementAndGet();
            assertThat(block.await(10, TimeUnit.SECONDS)).isTrue();
        };
        AtomicLong dropped = new AtomicLong();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordTrainingCandidatePublishDropped() {
                dropped.incrementAndGet();
            }
        };
        publisher = new AsyncTrainingCandidatePublisher(props, metrics, new TrainingPublishStatus(), slow);
        var t1 = Thread.ofVirtual().start(() -> publisher.publish(request(0.9, null, null)));
        Thread.sleep(50);
        publisher.publish(request(0.9, null, null));
        assertThat(dropped.get()).isEqualTo(1);
        block.countDown();
        t1.join();
    }

    @Test
    void transportFailureRecordsDegraded() throws Exception {
        SentinelProperties props = props(true, 1.0, 8, 0.0, false, 0.7);
        TrainingPublishStatus status = new TrainingPublishStatus();
        TrainingCandidateTransport bad = record -> {
            throw new IllegalStateException("kafka down");
        };
        AtomicLong failures = new AtomicLong();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordTrainingCandidatePublishFailure() {
                failures.incrementAndGet();
            }
        };
        publisher = new AsyncTrainingCandidatePublisher(props, metrics, status, bad);
        publisher.publish(request(0.9, null, null));
        Thread.sleep(200);
        assertThat(failures.get()).isEqualTo(1);
        assertThat(status.isDegraded()).isTrue();
    }

    @Test
    void highCompositeBypassesUniformSampleWhenStratified() throws Exception {
        SentinelProperties props = props(true, 1e-12, 8, 0.0, false, 0.7);
        props.getDistributed().setTrainingPublishStratifiedSampling(true);
        props.getDistributed().setTrainingPublishHighCompositeBypassSampleMinScore(0.4);
        CapturingTransport transport = new CapturingTransport();
        publisher = new AsyncTrainingCandidatePublisher(props, SentinelMetrics.NOOP, new TrainingPublishStatus(), transport);
        publisher.publish(request(0.95, null, null));
        Thread.sleep(300);
        assertThat(transport.records).hasSize(1);
    }

    @Test
    void publishedRecordHasEventIdAndEndpointFingerprint() throws Exception {
        SentinelProperties props = props(true, 1.0, 8, 0.0, false, 0.7);
        CapturingTransport transport = new CapturingTransport();
        publisher = new AsyncTrainingCandidatePublisher(props, SentinelMetrics.NOOP, new TrainingPublishStatus(), transport);
        publisher.publish(request(0.9, null, null));
        Thread.sleep(300);
        var r = transport.records.get(0);
        assertThat(r.eventId()).isNotBlank().hasSizeLessThanOrEqualTo(48);
        assertThat(r.endpointSha256Hex()).isEqualTo(TrainingFingerprintHashes.sha256HexUtf8("/e"));
        assertThat(r.enforcementKeySha256Hex()).hasSize(64);
    }

    @Test
    void exportedFeatureVectorsAreDefensiveCopies() throws Exception {
        SentinelProperties props = props(true, 1.0, 8, 0.0, false, 0.7);
        CapturingTransport transport = new CapturingTransport();
        publisher = new AsyncTrainingCandidatePublisher(props, SentinelMetrics.NOOP, new TrainingPublishStatus(), transport);
        publisher.publish(request(0.9, null, null));
        Thread.sleep(300);
        var r = transport.records.get(0);
        double[] mut = r.isolationForestFeatures();
        mut[0] = 999;
        assertThat(r.isolationForestFeatures()[0]).isNotEqualTo(999);
    }

    @Test
    void transportTimeoutRecordsKafkaTimeoutKind() throws Exception {
        SentinelProperties props = props(true, 1.0, 8, 0.0, false, 0.7);
        TrainingPublishStatus status = new TrainingPublishStatus();
        AtomicLong timeouts = new AtomicLong();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordTrainingCandidatePublishFailureTimeout() {
                timeouts.incrementAndGet();
            }
        };
        publisher = new AsyncTrainingCandidatePublisher(props, metrics, status, r -> {
            throw new TimeoutException("slow broker");
        });
        publisher.publish(request(0.9, null, null));
        Thread.sleep(200);
        assertThat(timeouts.get()).isEqualTo(1);
        assertThat(status.getLastErrorSummary()).startsWith("kafka_timeout:");
    }

    @Test
    void transportSerializationFailureRecordsSerializeFailedKind() throws Exception {
        SentinelProperties props = props(true, 1.0, 8, 0.0, false, 0.7);
        TrainingPublishStatus status = new TrainingPublishStatus();
        AtomicLong ser = new AtomicLong();
        SentinelMetrics metrics = new SentinelMetrics() {
            @Override
            public void recordTrainingCandidatePublishFailureSerialization() {
                ser.incrementAndGet();
            }
        };
        publisher = new AsyncTrainingCandidatePublisher(props, metrics, status, r -> {
            throw new JsonProcessingException("bad") {};
        });
        publisher.publish(request(0.9, null, null));
        Thread.sleep(200);
        assertThat(ser.get()).isEqualTo(1);
        assertThat(status.getLastErrorSummary()).startsWith("serialize_failed:");
    }

    private static TrainingCandidatePublishRequest request(double composite, Double stat, Double ifs) {
        RequestFeatures f = RequestFeatures.builder()
            .identityHash("id").endpoint("/e").timestampMillis(1L)
            .requestsPerWindow(1).endpointEntropy(0.5).tokenAgeSeconds(1)
            .parameterCount(2).payloadSizeBytes(3).headerFingerprintHash(4).ipBucket(5).build();
        return new TrainingCandidatePublishRequest(
            f,
            EnforcementAction.MONITOR,
            composite,
            stat,
            ifs,
            EnforcementScope.IDENTITY_ENDPOINT,
            "t1",
            "n1",
            "ENFORCE",
            true,
            false
        );
    }

    private static SentinelProperties props(boolean enabled, double sampleRate, int inFlight, double minScore,
                                            boolean antiPoison, double ifThreshold) {
        SentinelProperties p = new SentinelProperties();
        p.getDistributed().setTrainingPublishEnabled(enabled);
        p.getDistributed().setTrainingPublishSampleRate(sampleRate);
        p.getDistributed().setTrainingPublishMaxInFlight(inFlight);
        p.getDistributed().setTrainingPublishMinCompositeScore(minScore);
        p.getDistributed().setTrainingPublishApplyIfAntiPoisoning(antiPoison);
        p.getIsolationForest().setTrainingRejectionScoreThreshold(ifThreshold);
        return p;
    }

    private static final class CapturingTransport implements TrainingCandidateTransport {
        final List<io.aisentinel.distributed.training.TrainingCandidateRecord> records = new ArrayList<>();

        @Override
        public void send(io.aisentinel.distributed.training.TrainingCandidateRecord record) {
            records.add(record);
        }
    }
}
