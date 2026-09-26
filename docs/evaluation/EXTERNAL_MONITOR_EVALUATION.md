# External MONITOR evaluation

Concise handoff for an **independent** evaluator who will run AI-Sentinel in
**MONITOR** mode against an **authorized** Spring Boot / Servlet application and
return verifier-valid observational evidence.

This is **not** a detection-accuracy study, customer onboarding kit, or
deployment approval process.

`Handoff docs ≠ external run` · `MONITOR readiness ≠ completed pilot` · `Evaluation ≠ Deployment`

Full technical detail for the pilot workflow:
[`MONITOR_MODE_PILOT.md`](MONITOR_MODE_PILOT.md).

Configuration reference: [`../configuration.md`](../configuration.md).  
Deployment modes: [`../deployment.md`](../deployment.md).

---

## Authorization

**Only run this workflow against an application or environment you own or are
authorized to evaluate.**

Do not use employer or customer systems without explicit authorization.

---

## What this run establishes

If you complete a verifier-valid session and return the evidence package plus
attestation, the project may record that an independent evaluator executed the
MONITOR observational workflow outside maintainer-controlled synthetic runs.

It does **not** establish detection efficacy, production security effectiveness,
customer adoption, deployment approval, generalization, regulatory compliance,
or zero operational impact.

---

## Software identity (critical)

The MONITOR pilot evidence workflow was added after the published 0.4.0 Maven
artifacts. For this external evaluation, build AI-Sentinel from commit
**`3317ec6b042e09da40ea8fbdbb2c9b86598acd2b`** or a later commit that contains
the pilot workflow.

That commit is a `dev` tip (PR #149 merge). It is **not** a formal Maven/GitHub
Release by itself.

**Maven Central `0.4.0` does not include this pilot workflow** and is not
sufficient for this evaluation.

Preferred path:

1. Clone [ai-sentinel](https://github.com/CollinKabwama/ai-sentinel).
2. Check out `3317ec6b042e09da40ea8fbdbb2c9b86598acd2b` (or a later tip that
   still contains `docs/evaluation/MONITOR_MODE_PILOT.md` and the pilot package).
3. Record the full SHA: `git rev-parse HEAD`.
4. Build with **JDK 21** and install the local starter, for example:

```bash
mvn -pl ai-sentinel-spring-boot-starter -am install -DskipTests
```

5. Depend on that local `ai-sentinel-spring-boot-starter` artifact from the
   evaluator-controlled Spring Boot / Servlet application.

Record the exact commit SHA used in the attestation.

---

## Supported surface

- Spring Boot / Servlet starter
- `SentinelFilter`
- `ai.sentinel.mode=MONITOR`
- default `MonitorOnlyEnforcementHandler` (do **not** register a custom
  `EnforcementHandler` bean for this evaluation)
- local file evidence sink only

---

## Minimal configuration

Placeholders only — never commit real secrets.

```yaml
ai:
  sentinel:
    enabled: true
    mode: MONITOR
    distributed:
      training-publish-enabled: false
    pilot:
      enabled: true
      # Empty or non-existent directory; must not be a symlink
      output-directory: /path/to/empty-pilot-session-dir
      # Optional; ASCII letters/digits/._- only, 1–80 chars
      session-id: external-eval-001
      # Prefer environment injection; ≥ 16 UTF-8 bytes
      pseudonymization-secret: ${AI_SENTINEL_PILOT_PSEUDONYMIZATION_SECRET}
```

Environment example:

```bash
export AI_SENTINEL_PILOT_PSEUDONYMIZATION_SECRET="$(openssl rand -base64 32)"
```

**Do not** send the secret with the returned evidence package.  
**Do not** paste the secret into the attestation.  
**Do not** commit the secret.  
Discard or rotate it after the run if appropriate.

Prefer leaving Redis / Kafka / cluster-quarantine integrations off for this
reference evaluation.

---

## Evaluator workflow

1. Confirm authorization for the target application/environment.
2. Clone the AI-Sentinel repository.
3. Check out commit `3317ec6b042e09da40ea8fbdbb2c9b86598acd2b` (or a later
   commit that contains the MONITOR pilot workflow).
4. Record the full SHA: `git rev-parse HEAD`.
5. Use JDK 21.
6. Build/install locally as needed (`mvn -pl ai-sentinel-spring-boot-starter -am install -DskipTests`).
7. Add `ai-sentinel-spring-boot-starter` to an evaluator-controlled Spring Boot /
   Servlet application.
8. Apply the MONITOR + pilot configuration above.
9. Confirm training publish is OFF.
10. Confirm no custom `EnforcementHandler` bean.
11. Start the application.
12. Produce or observe **authorized** application traffic.
13. Stop the application cleanly so the pilot session finalizes (normal Spring
    shutdown).
14. Confirm the session directory contains exactly:
    - `observations.jsonl`
    - `pilot-summary.json`
    - `pilot-manifest.json`
15. From the AI-Sentinel repository checkout, run:

```bash
./scripts/verify-monitor-pilot-evidence.sh /path/to/pilot-session-dir
```

16. Require **PASS**. If FAIL, do **not** edit artifacts to force a pass, and do
    not treat the package as completed external evidence until integrity is
    understood.
17. Preserve the original evidence bytes unchanged (copy only for analysis).
18. Complete [`external-evaluator-attestation.template.md`](external-evaluator-attestation.template.md)
    (save as `external-evaluator-attestation.md`).
19. Submit the three artifacts + attestation (see submission checklist).

---

## Observation size guidance

Prefer:

- several hundred observations when convenient;
- more than one identity when natural to the app;
- enough runtime to pass warmup into normal activity.

A smaller authorized run can still demonstrate that the external workflow
executed. Do **not** claim statistical representativeness.

---

## Privacy

- Evidence uses pilot-scoped HMAC-pseudonymized identity and endpoint keys.
- Raw Authorization, Cookie, body, query/form, principal username/email, and
  client IP values are **not** intended to be persisted.
- You remain responsible for reviewing the three artifacts before sharing.
- **Never** include the pseudonymization secret, access tokens, raw application
  logs, or proprietary request payloads in the submission.

---

## Environment description (high level only)

State one of:

- local test application
- evaluator-controlled development application
- staging environment
- other authorized environment (one short phrase)

Do **not** include customer names, hostnames, IP addresses, usernames, or
proprietary architecture details.

---

## Stop conditions

Stop the evaluation if:

- AI-Sentinel alters or blocks requests unexpectedly;
- mode becomes `ENFORCE`;
- evidence appears to contain raw sensitive data;
- the verifier fails;
- the application experiences unacceptable operational problems;
- you lose authorization to continue.

---

## Submission checklist

**Required**

- [ ] `pilot-manifest.json`
- [ ] `observations.jsonl`
- [ ] `pilot-summary.json`
- [ ] `external-evaluator-attestation.md`

**Optional**

- [ ] verifier stdout/stderr capture
- [ ] short issue notes

**Never submit**

- HMAC / pseudonymization secret
- access tokens
- raw application logs
- customer identities
- proprietary request payloads

---

## Optional known-event labels

If you already have authorized known test events, labels **may** be provided
separately after the run as a sidecar document. Do not expect runtime
annotation support. Labels stay offline and separate from scoring. No red-team
requirement.

---

## Maintainer preservation (for received packages)

When evidence is received, preserve:

- original bytes unchanged;
- SHA-256 for every returned file;
- date received;
- evaluator attestation;
- exact AI-Sentinel commit/version stated by the evaluator.

Do not rewrite original evidence files. Analyze copies only.

---

## Allowed claim (maintainer use only, after a genuine verified external run)

After a genuine independent run is received and verifier-validated, a narrow
factual statement may be recorded:

> An independent external evaluator ran AI-Sentinel in MONITOR mode and produced
> a verifier-valid operational evidence package containing N observations.

Do **not** use that wording before such evidence exists.

Disallowed expansions include detection efficacy, production validation,
security effectiveness, customer adoption, deployment approval, generalization,
compliance, zero operational impact, and ENFORCE readiness.
