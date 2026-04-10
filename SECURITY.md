# Security

## Supported versions

Security fixes are developed on **`dev`** and promoted to **`main`** via the normal release flow. Critical vulnerabilities that require immediate public mitigation may be hotfixed directly on `main` at maintainer discretion; such fixes are merged back into `dev` promptly.

Use the latest commit or release tag for deployments. Older snapshots are not maintained on a separate long-term support schedule unless explicitly stated in the future.

---

## Reporting vulnerabilities

**Please do not** open public GitHub issues for unfixed vulnerability details.

- Report privately to the repository maintainers (use **GitHub Security Advisories** / **private security reporting** if enabled on the repo, or contact addresses listed in repository settings or maintainer profiles).
- Include: affected component, version or commit, reproduction steps, and impact assessment if you can.

Maintainers will acknowledge receipt when possible and coordinate a fix and disclosure timeline. This is a volunteer-driven open-source project; response times are best-effort, not a SLA.

---

## Security-related design choices

- **Fail-open** — Many optional paths (e.g. distributed Redis, async training publish) are designed so failures degrade to permissive behavior where documented, to avoid accidental total outage. This trades strict lockdown for availability; operators must tune flags and monitor metrics.
- **No raw PII in training/export** — Training candidate records use hashed fingerprints and numeric features, not raw URLs or bodies. See Phase 5.5 documentation in the root [`README.md`](README.md).
- **Identity as hash** — Features and enforcement keys use hashed identifiers; configure hashing and trust boundaries in your application.
- **Bounded processing** — Buffers, semaphores, and timeouts limit work on hot and async paths; they are not a substitute for network-level rate limiting or auth.

---

## Known limitations

- **Not a full WAF or IAM product** — AI-Sentinel complements auth and infrastructure controls; it does not replace them.
- **Distributed features depend on Redis/Kafka** — Misconfiguration, credential leaks, or broker compromise are outside this library’s scope; follow standard practices for secrets and network policy.
- **Filesystem model registry** — Phase 5.6 uses a shared filesystem layout; OS permissions and shared mounts are your responsibility.
- **Trainer dedup is JVM-local** — Duplicate `eventId` handling does not survive process restarts or multiple trainer instances without external coordination.

No security boundary is perfect; review changes in your own threat model before production use.
