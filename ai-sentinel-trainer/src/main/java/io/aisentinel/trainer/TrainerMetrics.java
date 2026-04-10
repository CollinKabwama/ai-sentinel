package io.aisentinel.trainer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** Micrometer counters and timers for {@code aisentinel.trainer.*} meters. */
@Component
public class TrainerMetrics {

    private final Counter candidatesReceived;
    private final Counter candidatesWrongTenant;
    private final Counter candidatesDuplicateEventId;
    private final Counter candidatesMalformed;
    private final Counter candidatesAdmissionRejected;
    private final Counter trainSuccess;
    private final Counter trainFailure;
    private final Counter artifactPublished;
    private final Timer trainDuration;

    public TrainerMetrics(MeterRegistry registry) {
        this.candidatesReceived = Counter.builder("aisentinel.trainer.candidates.received").register(registry);
        this.candidatesWrongTenant = Counter.builder("aisentinel.trainer.candidates.wrong_tenant").register(registry);
        this.candidatesDuplicateEventId = Counter.builder("aisentinel.trainer.candidates.duplicate_event_id").register(registry);
        this.candidatesMalformed = Counter.builder("aisentinel.trainer.candidates.malformed").register(registry);
        this.candidatesAdmissionRejected = Counter.builder("aisentinel.trainer.candidates.admission_rejected").register(registry);
        this.trainSuccess = Counter.builder("aisentinel.trainer.train.success").register(registry);
        this.trainFailure = Counter.builder("aisentinel.trainer.train.failure").register(registry);
        this.artifactPublished = Counter.builder("aisentinel.trainer.artifact.published").register(registry);
        this.trainDuration = Timer.builder("aisentinel.trainer.train.duration").register(registry);
    }

    public void received() {
        candidatesReceived.increment();
    }

    public void wrongTenant() {
        candidatesWrongTenant.increment();
    }

    public void duplicateEventId() {
        candidatesDuplicateEventId.increment();
    }

    public void malformed() {
        candidatesMalformed.increment();
    }

    public void admissionRejected() {
        candidatesAdmissionRejected.increment();
    }

    public void trainOk(long nanos) {
        trainDuration.record(nanos, TimeUnit.NANOSECONDS);
        trainSuccess.increment();
    }

    public void trainFailed(long nanos) {
        trainDuration.record(nanos, TimeUnit.NANOSECONDS);
        trainFailure.increment();
    }

    public void published() {
        artifactPublished.increment();
    }
}
