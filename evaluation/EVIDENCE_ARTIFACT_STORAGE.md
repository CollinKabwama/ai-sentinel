# Evidence / artifact storage strategy (Evaluation Kit)

This document records the durable storage strategy for Evaluation Kit evidence
artifacts. It answers what stays in Git, what may live outside Git, how artifact
identity is defined, and how an evaluator verifies retrieved bytes.

It does **not** claim that large public archives have already been uploaded, and
it does **not** implement independent reproduction packaging.

Claim boundaries:

- Archive strategy ≠ production validation
- Stored artifact ≠ independent reproduction
- Checksum verified ≠ signed / attested / trusted methodology
- Download ≠ adoption
- Evaluation ≠ deployment
- Evidence checkpoint ≠ software release

---

## 1. Central rule

```text
SOURCE DEFINITION
  → ARTIFACT IDENTITY (sha256 of exact bytes + sizeBytes)
  → STORAGE LOCATION (optional locator)
  → RETRIEVAL (when bytes are not Git-resident)
  → CHECKSUM VERIFICATION (mandatory)
  → EVALUATION
```

**Identity ≠ location.** A URL, Release asset name, object key, or branch tip
is transport metadata. The canonical content identity is lowercase hex SHA-256
of the exact archived bytes, plus `sizeBytes`.

---

## 2. Why this strategy exists now

Measured repository state (at strategy adoption):

| Scope | Approximate size |
|-------|------------------|
| `.git` | ~10 MiB |
| `evaluation/` | ~544 KiB |
| Kit reference tree | ~280 KiB |
| Largest historical events file | ~149 KiB |

There is **no present Git size emergency**. The strategy is preventative and
unlocks independent reproduction at scale without turning Git into a dump of
repeated result directories or very large corpora.

---

## 3. Artifact classes

| Class | Examples | Typical residency |
|-------|----------|-------------------|
| Source definition | Scenario JSON, generation specs, schemas | Git |
| Regenerable reference artifact | Deterministic kit-reference corpora | Git while compact; archive when large |
| Immutable evidence artifact | Accepted evaluation/comparison bundles | Digest in Git; bytes in archive when large |
| External dataset artifact | Evaluator-supplied datasets | External / private; digest reference in Git when shared |
| Historical baseline artifact | `evaluation/reference/`, detection reference baseline | Git while compact; treat as non-equivalent to regeneration |

---

## 4. Git residency policy

Git **should** retain:

- scenario / test-plan definitions
- generation specs and compact regenerable corpora used by CI/tests
- schemas and contract fixtures
- manifests, inventories, and checksums
- compact summaries and methodology docs
- scripts that verify or evaluate

Git **should not** accumulate:

- repeated generated report directories
- very large corpora or external datasets
- binary evidence packages that are not needed for offline unit/integration tests

**Current decision:** keep existing compact kit-reference corpora, the historical
reference dataset, detection reference baseline evidence, and contract fixtures
**in Git**. Do not migrate them solely for architectural purity.

---

## 5. Archive residency policy

Move bytes outside Git when **any** of the following is true:

- a single artifact approaches provider practical limits (GitHub warns near
  ~50 MiB and blocks files above ~100 MiB)
- repeated evaluation/comparison output trees would inflate history without
  improving offline testability
- disclosure is private or licensing forbids project rehosting
- independent evaluators need a redistributable public bundle larger than the
  compact Git-resident reference set

---

## 6. Selected public archive mechanism

**Selected (not yet deployed for large payloads):** GitHub Release **assets**
attached to **evidence checkpoint tags**.

Properties:

| Concern | Stance |
|---------|--------|
| Discoverability | Tag + asset name recorded in a tracked evidence-artifact reference |
| Public retrieval | Anonymous HTTPS download for public assets |
| Software-release coupling | Evidence tags are distinct from Maven/software SemVer unless intentionally aligned |
| Immutability | Release assets can be replaced/deleted by maintainers; **sha256 in Git remains authoritative** |
| Integrity | Always verify `sha256` (+ `sizeBytes`) after retrieval |
| Availability | Maintainer-operated Release retention; missing/expired assets are retrieval failures |

This selection satisfies the distribution surface “versioned corpus/evidence
archive” without requiring cloud credentials for public Level-1 evidence.

**Not selected as long-term evidence archive:**

| Mechanism | Reason |
|-----------|--------|
| GitHub Actions artifacts | Retention expiry; run-tied; not durable archival identity |
| Git LFS | Contributor/clone friction; quota coupling; weaker anonymous evaluator story |
| Maven Central | Wrong semantics for corpora/result bundles; Kit remains KEEP IN CORE |
| OCI / GHCR | Unnecessary complexity for arbitrary datasets/reports |
| Object storage (S3-compatible) | Deferred for private/org-controlled evidence when needed; not required for current public compact artifacts |

---

## 7. Private and licensed artifacts

| Disclosure | Meaning |
|------------|---------|
| `public` | Project may redistribute archive bytes |
| `private` | Organization-controlled; public locator may be omitted |
| `external-reference-only` | Project does **not** rehost bytes (licensing/ownership); evaluators supply matching local bytes |

Storage strategy does **not** grant permission to publish production logs, PII,
credentials, or customer traffic.

---

## 8. Recovery modes

| Mode | Use |
|------|-----|
| `retrieve-only` | Historical accepted evidence; external datasets; non-regenerable bundles |
| `regenerate-and-verify` | Deterministic generated corpora when generator identity + seed are sufficient |
| `retrieve-or-regenerate` | Either path acceptable; verification still required |

Do **not** silently regenerate a supposedly immutable historical evidence
artifact when the declared mode is `retrieve-only`.

---

## 9. Machine contract

Schema: [`docs/contracts/schemas/evaluation-kit/evidence-artifact.schema.json`](../docs/contracts/schemas/evaluation-kit/evidence-artifact.schema.json)

Required fields (conceptual):

- `artifactSchemaVersion`, `artifactId`, `artifactKind`
- `sha256`, `sizeBytes`
- `disclosure`, `recoveryMode`
- optional `location` (`git-path` | `github-release-asset` | `https-object`)
- optional `relatedIdentities` (corpus/dataset/result bindings)

Existing in-tree `artifactRef` path+`sha256` pairs remain the local sibling
pattern for corpus payload files. The evidence-artifact reference is for
**archive packages** and cross-storage discoverability.

`artifactId` is a stable **logical/discovery** identity (for humans and
tooling to refer to "this evidence package"), not the canonical bytes
identity. It is not required to be globally unique across all evidence ever
produced, and it must never substitute for `sha256`+`sizeBytes` when deciding
whether two artifacts are the same bytes. Two references could in principle
share an `artifactId` while pointing at different content revisions; only
`sha256`+`sizeBytes` decides byte identity.

---

## 10. Archive format (when packaging)

Preferred portable package: `.tar.gz` with relative paths only (no absolute
paths). Deterministic metadata (timestamps/uid/gid/order) should be sought when
practical, but **bit-reproducible archives are not claimed** unless separately
verified.

---

## 11. Verification workflow

Local verification (implemented):

```bash
scripts/verify-evidence-artifact.sh \
  --manifest docs/contracts/fixtures/evaluation-kit/valid/evidence-artifact.git-path.example.json \
  --artifact docs/contracts/fixtures/evaluation-kit/artifacts/sample-evidence.txt
```

Semantics:

1. Load tracked reference (`sha256`, `sizeBytes`)
2. Hash supplied local bytes
3. Fail on size mismatch, digest mismatch, missing file, or malformed reference
4. Never execute artifact contents
5. Do not upload; do not fetch remote objects in this capability

Future remote fetch (out of scope here), if added, must verify digest **after**
download. HTTPS success alone is not integrity.

Missing archive, expired URL, checksum mismatch, or size mismatch ⇒
**evidence-retrieval failure**.

---

## 12. Evaluation CLI boundary

Evaluation and comparison CLIs must **not** auto-upload results. Archival is an
explicit future action, separate from evaluation execution.

---

## 13. Relation to independent reproduction

An outsider reproducing public reference evidence needs, from the repository:

1. definitions / methodology
2. expected artifact digests (and sizes)
3. discoverable public locators when bytes are not Git-resident
4. verification before evaluation

This storage strategy supplies (2)–(3) and a local verifier for (4).

The Level-1 independent reproduction package lives under
[`evaluation/reproduction/`](reproduction/) with the outsider guide
[`docs/evaluation/INDEPENDENT_REPRODUCTION.md`](../docs/evaluation/INDEPENDENT_REPRODUCTION.md).
It regenerates selected kit-reference evidence and verifies SHA-256 / sizeBytes
locally. It does **not** upload remote Release assets and does not change this
storage architecture.

---

## 14. Deployment status

| Item | Status |
|------|--------|
| Strategy decision | Adopted |
| Evidence-artifact schema | Present |
| Local verify script | Present |
| Live GitHub Release evidence assets for large corpora | **Not deployed** (not required while compact artifacts remain Git-resident) |
| Object-storage private archive | Deferred |
| History rewrite / artifact migration | Not performed |
