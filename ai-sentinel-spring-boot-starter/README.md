# ai-sentinel-spring-boot-starter

Spring Boot integration for AI-Sentinel: **`SentinelFilter`**, **`SentinelAutoConfiguration`**,
**`SentinelProperties`** (`ai.sentinel.*`), servlet adapters (`ServletHttpRequestView`, `ServletEnforcementResponse`),
actuator **`/actuator/sentinel`**, Micrometer metrics, and optional Redis / Kafka / filesystem model-registry beans.

Depends on **`ai-sentinel-core`**. Custom beans replace defaults via `@ConditionalOnMissingBean`.

**Current tree version:** **0.3.0** (Maven Central latest published: **0.2.0**). Default `ai.sentinel.mode` is **`MONITOR`**; set **`ENFORCE`** only after operator validation — see [`docs/deployment.md`](../docs/deployment.md).

**Next:** [Root README](../README.md) · [Configuration](../docs/configuration.md) · [Migration](../docs/migration.md) · [Architecture](../ARCHITECTURE.md)
