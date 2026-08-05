# ai-sentinel-demo

Minimal Spring Boot application (`/api/hello`) with the starter on the classpath and actuator exposed.
Used for smoke tests (`DemoIntegrationTest`) and local manual runs.

```bash
mvn -pl ai-sentinel-demo spring-boot:run
```

Optional **`stage2`** profile speeds Isolation Forest training for experiments (`application-stage2.yaml`).

**Next:** [Root README](../README.md)
