# ai-sentinel-spring-boot-starter

Spring Boot integration for AI-Sentinel: **`SentinelFilter`**, **`SentinelAutoConfiguration`**,
**`SentinelProperties`** (`ai.sentinel.*`), servlet adapters (`ServletHttpRequestView`, `ServletEnforcementResponse`),
actuator **`/actuator/sentinel`**, Micrometer metrics, and optional Redis / Kafka / filesystem model-registry beans.

Depends on **`ai-sentinel-core`**. Custom beans replace defaults via `@ConditionalOnMissingBean`.

**Current version:** **0.4.0** (published; tag [`v0.4.0`](https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.4.0)). Previous published Central line: **0.3.0** (tag `v0.3.0`). Default `ai.sentinel.mode` is **`MONITOR`**; set **`ENFORCE`** only after operator validation — see [`docs/deployment.md`](../docs/deployment.md).

Candidate scorer validation/loading/evaluation/shadow/lifecycle types live in **`ai-sentinel-core`** and are packaged in **0.4.0**. Ordinary starter usage does **not** require configuring a candidate scorer, shadow scoring, or lifecycle governance. Lifecycle promotion does not rewire the starter's authoritative scorer (`PROMOTED != PRODUCTION DEPLOYED`).

**Next:** [Root README](../README.md) · [Configuration](../docs/configuration.md) · [Migration](../docs/migration.md) · [Architecture](../ARCHITECTURE.md) · [Candidate contracts](../docs/contracts/SCORER_ARTIFACT.md)
