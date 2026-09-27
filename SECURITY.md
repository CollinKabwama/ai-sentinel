# Security

## Supported versions

| Line | Status |
|------|--------|
| **0.4.0** (tag `v0.4.0`) | Current published Maven Central release |
| **0.3.0** (tag `v0.3.0`) | Previous published release |
| **0.2.0** and earlier | Not maintained; upgrade via [`docs/migration.md`](docs/migration.md) |
| Unreleased `dev` commits | Integration tip; not a supported production pin unless you intentionally build from source |

Security fixes are developed on **`dev`** and promoted to **`main`** through the normal release flow. Critical vulnerabilities that need immediate mitigation may be hotfixed on `main` at maintainer discretion and merged back into `dev` promptly. Prefer the latest published release tag for deployments.

---

## Reporting vulnerabilities

**Please do not** open public GitHub issues for unfixed vulnerability details.

- Report privately to the maintainers (GitHub **private security reporting** / Security Advisories if enabled, or contact details in repository settings or maintainer profiles).
- Include the affected component, version or commit, reproduction steps, and impact assessment if you can.

This is a volunteer-driven project; response times are best-effort, not an SLA.

---

## What AI-Sentinel is (and is not)

AI-Sentinel is an application/API-layer behavioral-risk evaluation framework for post-authentication traffic. It complements authentication, authorization, and infrastructure controls. It is **not** an identity provider, MFA system, WAF, SIEM, IAM product, or complete Zero Trust architecture.

Current deployable surfaces: the Java 21 decision core, the Spring Boot / Servlet starter, the optional authenticated remote evaluation API, and the ASP.NET Core reference client in [`dotnet/`](dotnet/) (a remote client, not a .NET detection engine).

---

## Security controls provided

**Remote evaluation API**

- `POST /ai-sentinel/v1/evaluation` authenticates only through the `X-AI-Sentinel-Api-Key` header (not the query string, not end-user `Authorization`). Comparison is constant-time.
- Missing, blank, duplicate/ambiguous, or incorrect keys are rejected with **401**; the secret is never echoed. Duplicate header values are rejected rather than silently picking one.
- This is a shared-secret service boundary, not OAuth/OIDC/mTLS.

**Sensitive metadata restrictions**

- Local adapters map only an allowlist of safe headers into `EvaluationRequest`; `Authorization` is recorded as presence only (`present`).
- The wire contract rejects credential-bearing header keys (`cookie`, `set-cookie`, `proxy-authorization`, `x-ai-sentinel-api-key`, `x-api-key`) and any `authorization` value other than the presence marker.
- Features and enforcement keys use hashed identifiers. Training candidates carry hashed fingerprints and numeric features, not raw URLs or bodies. Evaluation events and MONITOR pilot observations use privacy-safe schemas without Authorization, Cookie, or raw body fields.
- MONITOR pilot evidence HMAC-pseudonymizes identity and endpoint keys with a required secret (minimum length enforced). The secret is never written to artifacts or logs, and failure never falls back to storing raw identity.
- Redis failure DEBUG logs omit Redis key material and logical identity keys.

**Evidence protection**

- Evaluation Kit and candidate evaluation tooling refuse to write into protected reference locations (`evaluation/detection-reference-baseline`, `evaluation/reference`, `docs/performance`) and refuse to overwrite an existing output directory. Existing path prefixes are resolved with real-path semantics, so symlinked parents are not treated as safe.
- Manifests use SHA-256 + `sizeBytes` identity where applicable. This is integrity governance, not cryptographic signing or PKI.

**Bounded processing**

- Buffers, semaphores, and timeouts bound work on hot and async paths. They do not replace network-level rate limiting or authentication.

---

## Integration responsibilities

- **Secrets.** Inject the remote evaluation API key and the pilot pseudonymization secret through environment variables or a secret store; never commit them. Protect the API key like any service credential.
- **Identity.** Prefer authenticated principals. Without one, identity falls back to a hash of the resolved client IP, which suffers from NAT pooling, IP churn, and weak attribution — see [`docs/configuration.md`](docs/configuration.md#unauthenticated-identity-ip-hash-and-state-growth).
- **Proxies.** Keep `ai.sentinel.trusted-proxies` tight; forwarded headers are honored only from trusted hops.
- **Filter order.** The default filter order is late so authentication can populate the principal. Running earlier forces IP-only identity. If another filter commits the response first, denial writes are skipped while quarantine/throttle state may still apply — see [`docs/configuration.md`](docs/configuration.md).
- **Enforcement scope.** `IDENTITY_GLOBAL` throttle/quarantine keys span all endpoints for an identity; choose it deliberately.
- **Infrastructure.** Redis, Kafka, and shared filesystem registries must be secured, sized, and access-controlled by the operator; misconfiguration or compromise of those systems is outside the library's scope. Registry artifacts are not pruned automatically ([`docs/deployment.md`](docs/deployment.md#model-registry-disk-retention)).
- **Mode.** Start in `MONITOR`. `ENFORCE` is not claimed production-ready from synthetic tests; it requires application-specific validation and the preconditions in [`docs/deployment.md`](docs/deployment.md).

---

## Failure behavior

AI-Sentinel is **availability-first (fail-open)**. Request-path and optional-path failures generally let traffic proceed rather than deny it, and there is **no fail-closed profile**. The canonical failure matrix is in [`docs/deployment.md`](docs/deployment.md#failure-mode-profile-availability-first).

When a remote client cannot obtain a trusted response (auth rejection, transport error, timeout, malformed body, unsupported `contractVersion`, unknown action), it returns `REMOTE_EVALUATION_FAILURE` and may fail open. That result is **not** a trusted engine `ALLOW`.

---

## Known limitations

- **API keys** — no rotation service, per-caller identity, or resistance to credential theft or insider misuse.
- **Distributed state** — when distributed trust Redis is unavailable, each node falls back to in-memory baselines; those observations are not reconciled into Redis after recovery ([`docs/deployment.md`](docs/deployment.md#distributed-deployment-notes)).
- **Trainer deduplication** — `eventId` dedup is JVM-local and does not survive restarts or multiple trainer instances.
- **Client-influenced features** — behavioral features (payload size, parameter counts, token-age headers) can be shaped by clients; they are weak signals, not identity assertions.

## Not claimed

Production security certification, compliance attestation, Zero Trust certification, external penetration testing, privacy certification, complete secret management, signed artifact PKI, production detection efficacy, or complete protection against compromise. Repository hardening is not production security certification.

No security boundary is perfect. Review AI-Sentinel in your own threat model; production readiness is an operator judgment, not a property of the library alone.

Related: [`ARCHITECTURE.md`](ARCHITECTURE.md) · [`docs/deployment.md`](docs/deployment.md) · [`docs/configuration.md`](docs/configuration.md) · [`CHANGELOG.md`](CHANGELOG.md)
