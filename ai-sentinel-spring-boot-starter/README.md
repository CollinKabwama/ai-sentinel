# ai-sentinel-spring-boot-starter

Spring Boot integration for AI-Sentinel: **`SentinelFilter`**, **`SentinelAutoConfiguration`**,
**`SentinelProperties`** (`ai.sentinel.*`), servlet adapters (`ServletHttpRequestView`, `ServletEnforcementResponse`),
actuator **`/actuator/sentinel`**, Micrometer metrics, and optional Redis / Kafka / filesystem model-registry beans.

Depends on **`ai-sentinel-core`**. Custom beans replace defaults via `@ConditionalOnMissingBean`.

**Current version:** **0.3.0** (Maven Central, tag `v0.3.0`). Default `ai.sentinel.mode` is **`MONITOR`**; set **`ENFORCE`** only after operator validation — see [`docs/deployment.md`](../docs/deployment.md).

Candidate scorer validation/loading/evaluation/shadow/lifecycle types live in **`ai-sentinel-core`** on the **current development line** (listed under root [`CHANGELOG.md` Unreleased](../CHANGELOG.md)); they are **not** present in the published Maven Central **0.3.0** jars. Ordinary starter usage does **not** require configuring a candidate scorer, shadow scoring, or lifecycle governance. Lifecycle promotion does not rewire the starter's authoritative scorer (`PROMOTED != PRODUCTION DEPLOYED`).

**Next:** [Root README](../README.md) · [Configuration](../docs/configuration.md) · [Migration](../docs/migration.md) · [Architecture](../ARCHITECTURE.md) · [Candidate contracts](../docs/contracts/SCORER_ARTIFACT.md)
