# AI-Sentinel

AI-Sentinel is an **application/API-layer behavioral-risk evaluation framework** for HTTP/API workloads. It evaluates **post-authentication** request behavior, produces a risk decision, and lets the host application respond (allow, monitor, throttle, block, or quarantine).

It is designed to **complement** identity, authorization, network, and monitoring controls. It is **not** a complete Zero Trust architecture, an identity provider, an MFA system, a WAF, a SIEM, or a compliance certification, and repository evidence is not proof of production efficacy.

---

## What problem it addresses

Static rules and coarse rate limits miss gradual or identity-specific abuse by already-authenticated callers. AI-Sentinel keeps per-identity behavioral baselines and turns deviations into a single, configurable policy decision.

## What it does today

- **Behavioral features** — privacy-oriented request features (volume, endpoint entropy/concentration, payload shape, token age, header fingerprint, coarse IP bucket). No raw tokens or bodies.
- **Anomaly scoring** — Statistical/Welford baselines, with an optional in-core Isolation Forest blended by the Composite scorer.
- **Optional identity trust and risk fusion** — combine anomaly risk with behavioral trust before policy.
- **Policy** — threshold bands map a score in `[0,1]` to `ALLOW`, `MONITOR`, `THROTTLE`, `BLOCK`, or `QUARANTINE`.
- **Operating modes** — `OFF`, `MONITOR` (default; evaluates and learns, never denies), and `ENFORCE` (explicit opt-in).
- **Integration surfaces** — in-process Spring Boot / Servlet starter; optional authenticated remote evaluation API; .NET reference client.
- **Optional distributed state** — Redis-backed quarantine, throttle, and trust baselines; Kafka training export; standalone trainer with a filesystem model registry.
- **Offline evaluation tooling** — deterministic replay, evaluation reports, and reference corpora for repository-controlled evidence (not on the request path).
- **Candidate scorer lifecycle (opt-in)** — validate, load, evaluate, shadow-score, and designate candidate models without changing the running production scorer. See [`docs/README.md`](docs/README.md#candidate-scorer-lifecycle).
- **Observability** — Micrometer meters (`aisentinel.*`), structured JSON telemetry, and `GET /actuator/sentinel`.

## What it does not claim

- Production detection efficacy or production-ready `ENFORCE` from synthetic tests.
- That anomalous behavior is malicious, that normal behavior is safe, or that an anomaly score is a probability of attack.
- That `MONITOR` results, evaluation runs, or repository tests amount to a deployment or an external pilot.
- Security certification, formal Zero Trust certification, or complete protection against compromise.

Limitations and failure behavior: [`SECURITY.md`](SECURITY.md) and [`docs/deployment.md`](docs/deployment.md).

---

## Modules

| Module | Role |
|--------|------|
| `ai-sentinel-core` | Framework-independent Java decision engine (features, scorers, policy, enforcement contracts, offline evaluation). No Spring, Servlet, or Reactor. |
| `ai-sentinel-spring-boot-starter` | Spring Boot / Servlet adapter: auto-configuration, `SentinelFilter`, `ai.sentinel.*` properties, actuator, Micrometer, optional Redis/Kafka, remote evaluation API. |
| `ai-sentinel-trainer` | Optional standalone app: consumes training candidates, trains Isolation Forest models, publishes to a filesystem registry. |
| `ai-sentinel-demo` | Reference Spring Boot application and smoke tests. |
| `ai-sentinel-benchmark` | Opt-in JMH / deployment / resource benchmarks (not published; not a detection benchmark). |
| `dotnet/` | ASP.NET Core reference client for the remote evaluation API. Not a C# detection engine. |

Component boundaries and data flow: [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## Quick start

**Prerequisites:** Java 21 (the supported and CI-tested JDK) and Maven 3.8+. Optional: Docker for Redis-backed tests; .NET 8 SDK for `dotnet/`.

Add the starter (current release **0.4.0**, [Maven Central](https://central.sonatype.com/artifact/dev.aisentinel/ai-sentinel-spring-boot-starter/0.4.0)):

```xml
<dependency>
    <groupId>dev.aisentinel</groupId>
    <artifactId>ai-sentinel-spring-boot-starter</artifactId>
    <version>0.4.0</version>
</dependency>
```

Minimal configuration:

```yaml
ai:
  sentinel:
    enabled: true
    mode: MONITOR   # default; enable ENFORCE only after MONITOR validation
```

Build and run the demo from source:

```bash
mvn clean install
mvn -pl ai-sentinel-demo spring-boot:run
# http://localhost:8080/api/hello  and  http://localhost:8080/actuator/sentinel
```

Start in `MONITOR`. Enabling `ENFORCE` is an explicit operator decision after application-specific validation — see [`docs/deployment.md`](docs/deployment.md).

---

## Where to go next

| Topic | Document |
|-------|----------|
| Architecture and extension points | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Configuration properties | [`docs/configuration.md`](docs/configuration.md) |
| Operating modes, adoption, failure behavior, Redis operations | [`docs/deployment.md`](docs/deployment.md) |
| Security properties and limitations | [`SECURITY.md`](SECURITY.md) |
| Offline evaluation and evidence boundaries | [`evaluation/DETECTION_EVALUATION.md`](evaluation/DETECTION_EVALUATION.md) · [`docs/README.md`](docs/README.md#evaluation) |
| Performance evidence | [`docs/performance/REFERENCE_BASELINE.md`](docs/performance/REFERENCE_BASELINE.md) |
| .NET reference client | [`dotnet/README.md`](dotnet/README.md) |
| Testing and release gates | [`docs/testing.md`](docs/testing.md) |
| Upgrading | [`docs/migration.md`](docs/migration.md) · [`CHANGELOG.md`](CHANGELOG.md) |
| Scripts | [`scripts/README.md`](scripts/README.md) |
| Full documentation index | [`docs/README.md`](docs/README.md) |

---

## Contributing

Development targets the `dev` branch. See [`CONTRIBUTING.md`](CONTRIBUTING.md) and the [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md). Report vulnerabilities privately as described in [`SECURITY.md`](SECURITY.md).

## License

MIT — see [`LICENSE`](LICENSE).
