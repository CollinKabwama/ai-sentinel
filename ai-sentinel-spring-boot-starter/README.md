# ai-sentinel-spring-boot-starter

Spring Boot integration for AI-Sentinel: **`SentinelFilter`**, **`SentinelAutoConfiguration`**,
**`SentinelProperties`** (`ai.sentinel.*`), servlet adapters (`ServletHttpRequestView`, `ServletEnforcementResponse`),
actuator **`/actuator/sentinel`**, Micrometer metrics, and optional Redis / Kafka / filesystem model-registry beans.

Depends on **`ai-sentinel-core`**. Custom beans replace defaults via `@ConditionalOnMissingBean`.

**Next:** [Root README](../README.md) · [Configuration](../docs/configuration.md) · [Architecture](../ARCHITECTURE.md)
