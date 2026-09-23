# External MONITOR evaluator attestation

Complete this factual form after a MONITOR observational run.  
This is an **execution attestation**, not an endorsement or testimonial.

Do **not** describe AI-Sentinel as effective, secure, production-ready, or
recommended in this document.

Copy this file as `external-evaluator-attestation.md` and fill in the fields.

---

## Evaluator (optional identity)

- Name or professional identifier (if you consent):
- Organization / affiliation (if you consent):
- Contact (optional):

## Evaluation metadata

- Date of evaluation (UTC or local with timezone):
- AI-Sentinel commit SHA used:
- How software was obtained (clone commit / local build / other):
- JDK version used to build or run (if known):
- Application type (Spring Boot / Servlet): yes / no
- High-level environment type (choose one):
  - [ ] local test application
  - [ ] evaluator-controlled development application
  - [ ] staging environment
  - [ ] other authorized environment (one short phrase, no hostnames/IPs):

## Authorization

- I confirm I own or am authorized to evaluate the selected application/environment: yes / no
- I confirm I did **not** include the pilot pseudonymization secret in this submission: yes / no

## Runtime posture

- `ai.sentinel.mode` was `MONITOR` for the evidence session: yes / no
- Training publish was disabled (`training-publish-enabled=false`): yes / no
- No custom `EnforcementHandler` bean was registered for this evaluation: yes / no / unsure

## Session scale (approximate is fine)

- Approximate duration of observation:
- Approximate observation count (or `observationCount` from `pilot-manifest.json`):
- Multiple identities observed (yes / no / unsure):

## Verifier

- Command run: `./scripts/verify-monitor-pilot-evidence.sh <pilot-dir>`
- Verifier result: **PASS** / **FAIL**
- If FAIL, brief note:

## Non-enforcement observation

- Did you observe AI-Sentinel blocking or altering HTTP request outcomes during
  this MONITOR session? yes / no / unsure
- If yes, describe briefly (no sensitive payloads):

## Evidence integrity

- The attached `pilot-manifest.json`, `observations.jsonl`, and
  `pilot-summary.json` are the artifacts produced by this run: yes / no
- I reviewed the artifacts for unexpected raw sensitive values before sharing:
  yes / no

## Operational issues

- Any operational issue encountered (or “none”):

## Optional notes

- Short notes (optional; no secrets, no proprietary payloads):

---

### Attestation statement

I attest that the statements above are accurate to the best of my knowledge and
that this MONITOR evaluation was performed under authorization on the stated
environment.

Signature / typed name:  
Date:
