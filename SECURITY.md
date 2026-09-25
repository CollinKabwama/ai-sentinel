# Security

## Supported versions

| Line | Status |
|------|--------|
| **0.4.0** (tag `v0.4.0`) | Current published Maven Central release (candidate/shadow/lifecycle engineering capability). |
| **0.3.0** (tag `v0.3.0`) | Previous published Maven Central release |
| **0.2.0** | Previous published line; upgrade via [`docs/migration.md`](docs/migration.md) |
| Unreleased `dev` commits | Integration tip; not a supported production pin unless you intentionally build from source |

Security fixes are developed on **`dev`** and promoted to **`main`** via the normal release flow. Critical vulnerabilities that require immediate public mitigation may be hotfixed directly on `main` at maintainer discretion; such fixes are merged back into `dev` promptly.

Prefer the latest **published** release tag for deployments. Older snapshots are not maintained on a separate long-term support schedule unless explicitly stated in the future.

---

## Reporting vulnerabilities

**Please do not** open public GitHub issues for unfixed vulnerability details.

- Report privately to the repository maintainers (use **GitHub Security Advisories** / **private security reporting** if enabled on the repo, or contact addresses listed in repository settings or maintainer profiles).
- Include: affected component, version or commit, reproduction steps, and impact assessment if you can.

Maintainers will acknowledge receipt when possible and coordinate a fix and disclosure timeline. This is a volunteer-driven open-source project; response times are best-effort, not a SLA.

---

## Security-related design choices

- **Fail-open (availability-first)** — Request-path and optional-path failures generally **allow traffic to proceed** rather than deny it. This trades strict lockdown for availability. The canonical matrix (detector, Redis, trust, enforcement writes, feature extraction, trainer) is in [`docs/deployment.md`](docs/deployment.md#failure-mode-profile-availability-first). Prefer **`MONITOR`** until fail-open rates and enforcement decisions are understood in your traffic.
- **Remote evaluation API key** — Optional `POST /ai-sentinel/v1/evaluation` authenticates solely via the `X-AI-Sentinel-Api-Key` header (not query string, not end-user `Authorization`). Comparison is constant-time. Missing, blank, duplicate/ambiguous, or incorrect credentials are rejected with **401** and never echo the secret. Duplicate header values are rejected (no silent first-value selection). This is a shared-secret adapter boundary, not OAuth/OIDC/mTLS.
- **Remote evaluation fail-open on clients** — When a remote client cannot obtain a trusted evaluation response (auth rejection, transport/timeout, malformed body, unsupported `contractVersion`, unknown action), the client returns a **remote-evaluation failure** result that may **fail-open proceed**. That is **not** a trusted engine `ALLOW` decision (`REMOTE_EVALUATION_FAILURE ≠ trusted engine ALLOW`).
- **Contract header privacy** — Local adapters map only a safe header allowlist into `EvaluationRequest`; `Authorization` is presence-only (`present`). The wire contract rejects credential-bearing header keys (`cookie`, `set-cookie`, `proxy-authorization`, `x-ai-sentinel-api-key`, `x-api-key`) and non-sentinel `authorization` values so remote callers cannot smuggle raw secrets into evaluation.
- **No raw PII in training/export** — Training candidate records use hashed fingerprints and numeric features, not raw URLs or bodies. Evaluation events and MONITOR pilot observations use explicit privacy-safe schemas (no Authorization/Cookie/raw body fields). See training export properties in [`docs/configuration.md`](docs/configuration.md) and the root [`README.md`](README.md).
- **Identity as hash** — Features and enforcement keys use hashed identifiers; configure hashing and trust boundaries in your application.
- **Unauthenticated identity falls back to client IP hash** — Without an authenticated principal, baselines and enforcement keys are per resolved client IP. Expect NAT pooling, IP-churn cardinality, and weaker attribution — see [`docs/configuration.md`](docs/configuration.md#unauthenticated-identity-ip-hash-and-state-growth). Prefer authenticated identity for production keys.
- **Pilot pseudonymization** — MONITOR pilot evidence HMAC-pseudonymizes identity/endpoint keys with a required secret (minimum length enforced). The secret must never appear in artifacts or logs; failure does not fall back to persisting raw identity.
- **Accepted evidence protection** — Evaluation Kit / candidate evaluation tooling refuses writing into protected accepted/reference locations (`evaluation/detection-reference-baseline`, `evaluation/reference`, `docs/performance`) and refuses overwriting an existing evidence output directory. Generated evidence ≠ silent mutation of accepted reference evidence. Manifests use SHA-256 + sizeBytes identity where applicable; this is integrity governance, not cryptographic signing or PKI.
- **Redis diagnostics** — Redis failure DEBUG logs omit Redis key material and logical identity keys (exception summary only). Do not reintroduce key values into application logs.
- **Bounded processing** — Buffers, semaphores, and timeouts limit work on hot and async paths; they are not a substitute for network-level rate limiting or auth.
- **Filter order vs identity** — Default Sentinel filter order is late so authentication can populate principal-based identity; place earlier only when you accept IP-only identity and need earlier denial. If another filter commits the response first, denial HTTP writes are skipped while quarantine/throttle state may still apply — see [`docs/configuration.md`](docs/configuration.md).
- **Enforcement scope blast radius** — `IDENTITY_GLOBAL` throttle/quarantine keys span all endpoints for an identity; statistical scoring remains per endpoint.
- **ENFORCE is not claimed production-ready from synthetic tests alone** — enabling client denial requires application-specific MONITOR evaluation and the preconditions in [`docs/deployment.md`](docs/deployment.md).

---

## Known limitations

- **Not a full WAF or IAM product** — AI-Sentinel complements auth and infrastructure controls; it does not replace them.
- **Current deployable surfaces** — Java **21** decision core (framework-independent: no Spring/Servlet APIs) plus Spring Boot / Servlet starter. Optional authenticated **remote evaluation HTTP API** for out-of-process clients. A reference **ASP.NET Core** adapter under [`dotnet/`](dotnet/) consumes that API (thin remote client; not a native .NET scoring engine). Framework-independent does **not** mean language-independent.
- **API-key limitations** — Shared-secret remote evaluation auth does not provide rotation services, per-caller identity, or resistance to credential theft / insider misuse. Protect the key like any other service secret.
- **Distributed features depend on Redis/Kafka** — Misconfiguration, credential leaks, or broker compromise are outside this library’s scope; follow standard practices for secrets and network policy.
- **Filesystem model registry** — Shared filesystem layout (`active.json` pointer + versioned artifacts). Publishing a new model does **not** delete prior artifact files; operators manage disk retention (see [`docs/deployment.md`](docs/deployment.md#model-registry-disk-retention)). OS permissions and shared mounts are your responsibility.
- **Trainer dedup is JVM-local** — Duplicate `eventId` handling does not survive process restarts or multiple trainer instances without external coordination.
- **No fail-closed profile** — Availability-first failure modes are intentional today; do not assume denial on component failure.
- **Not claimed here** — Production-hardened security certification, compliance attestations, zero-trust certification, external penetration testing, privacy certification, secret-management completeness, or signed artifact PKI.

No security boundary is perfect; review changes in your own threat model before production use.
This project is an early open-source release; the current published line is **0.4.0** (tag [`v0.4.0`](https://github.com/CollinKabwama/ai-sentinel/releases/tag/v0.4.0)). Treat production readiness as an operator judgment, not a claim of the library alone.

Related: [`docs/deployment.md`](docs/deployment.md) · [`CHANGELOG.md`](CHANGELOG.md) · [`docs/migration.md`](docs/migration.md)
