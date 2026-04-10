# ai-sentinel-trainer

Standalone Spring Boot application that **consumes** training candidates (from Kafka when enabled), **trains** an in-core Isolation Forest model, and **publishes** artifacts to a **filesystem** model registry layout that serving nodes read via `ModelRegistryReader` / `ModelRefreshScheduler`.

---

## Purpose

1. **Consume** JSON lines matching `TrainingCandidateRecord` (schema v2) — the same events Phase 5.5 can publish from starter nodes (`ai.sentinel.distributed.training-publish-enabled`, optional Kafka).
2. **Train** using the same `IsolationForestTrainer` / codec stack as the core library.
3. **Publish** `{version}.meta.json`, `{version}.payload.bin`, and `active.json` under `aisentinel.trainer.registry.filesystem-root` / `{tenantId}/`.

This module does **not** run inside your API process; it is a separate deployable.

---

## Relation to Phase 5.5

Starter apps **publish** candidates asynchronously (log or Kafka). **Trainer** is the **consumer** side: when `aisentinel.trainer.kafka.enabled=true` and a broker is configured, it subscribes to `aisentinel.trainer.kafka.topic` (default `aisentinel.training.candidates` — align with `ai.sentinel.distributed.training-candidates-topic` on producers). Without Kafka, the app still starts but **no messages are consumed** unless you extend the codebase.

---

## Architecture (brief)

- **`TrainerKafkaListener`** — `@KafkaListener` when `kafka.enabled=true`.
- **`TrainerOrchestrator`** — Parses JSON, admission gates, bounded buffer, scheduled train cycle, `FilesystemArtifactPublisher`.
- **Buffer** — FIFO cap (`buffer.max-samples`); train cycle **drains** for training and restores on failure.

---

## Run

Prerequisites: **Java 21**, **Maven**, root build `mvn clean install` from `ai-sentinel` parent.

```bash
cd /path/to/ai-sentinel
mvn -pl ai-sentinel-trainer spring-boot:run
```

With Kafka (example — set broker and enable the consumer in config):

```bash
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
mvn -pl ai-sentinel-trainer spring-boot:run -Dspring-boot.run.arguments=--aisentinel.trainer.kafka.enabled=true
```

Or set `aisentinel.trainer.kafka.enabled: true` in `application.yml` / your profile.

Configure `MODEL_REGISTRY_ROOT` or `aisentinel.trainer.registry.filesystem-root` so serving nodes can point `ai.sentinel.model-registry.filesystem-root` at the **same** directory.

---

## Configuration (`aisentinel.trainer.*`)

| Area | Property prefix | Notes |
|------|-----------------|--------|
| Tenant | `tenant-id` | Must match producer `ai.sentinel.distributed.tenant-id` for admitted rows. |
| Registry | `registry.filesystem-root` | Output root (default `./var/aisentinel-model-registry`). |
| Buffer | `buffer.max-samples` | Max training rows (default 50_000). |
| Train | `train.interval-millis`, `train.min-samples` | Schedule and minimum buffer size before training. |
| IF | `if-model.num-trees`, `max-depth`, `random-seed` | Training hyperparameters. |
| Admission | `admission.min-composite-score`, `max-isolation-forest-score` | Candidate filters. |
| Dedup | `dedup.max-recent-event-ids` | Bounded `eventId` LRU; `0` disables. |
| Kafka | `kafka.enabled`, `kafka.topic`, `kafka.group-id` | Consumer wiring. |

Spring Boot also binds `spring.kafka.*` (see `application.yml` for bootstrap defaults).

---

## Metrics (Micrometer)

Prefix **`aisentinel.trainer.`**, including:

- `candidates.received`, `candidates.malformed`, `candidates.admission_rejected`, `candidates.wrong_tenant`, `candidates.duplicate_event_id`
- `train.success`, `train.failure`, `train.duration`, `artifact.published`

Expose Prometheus if `micrometer-registry-prometheus` is on the classpath (see module `pom.xml`).

---

## Limitations

- **Filesystem registry only** — No built-in S3/Redis artifact store; operators manage disk and permissions.
- **JVM-local `eventId` dedup** — Restarts and multiple trainer instances can see duplicates; no distributed leader election.
- **No multi-node trainer coordination** — One trainer instance per logical pipeline is assumed; scale-out requires external design.
- **Kafka required for live ingestion** — With `kafka.enabled=false`, wire your own feed or enable Kafka for production-style runs.

For end-to-end flow and node-side refresh, see root [`README.md`](../README.md) Phase 5.5 / 5.6 and [`ARCHITECTURE.md`](../ARCHITECTURE.md) §10.
