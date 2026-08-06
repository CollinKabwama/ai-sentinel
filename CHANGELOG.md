# Changelog

All notable changes to AI-Sentinel are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased] — 0.2.0

### Changed

- **Statistical warmup is no longer enforcement by accident (R-037).**
  While a per-identity|endpoint statistical baseline has fewer than `ai.sentinel.warmup-min-samples` samples,
  the decision engine applies `ai.sentinel.warmup-action` (default **`MONITOR`**) instead of mapping the
  numeric `warmup-score` (default `0.4`) through policy thresholds. That removes the previous collision with
  the default THROTTLE band (`threshold-elevated = 0.4`).
- **`warmup-score` retained** for telemetry / fusion input. Enforcement during warmup is controlled by
  **`warmup-action`**. Precedence: policy → trust adjust → **warmup action if `STATISTICAL_WARMUP`** →
  startup grace → quarantine override.

### Added

- **`EvaluationStatus` on `RiskDecision` (R-043):** immutable `Set<EvaluationStatus>` including at least
  `STATISTICAL_WARMUP`, `STATISTICAL_LIVE`, `MODEL_UNAVAILABLE`, `MODEL_FALLBACK_USED`, and `COMPLETE`
  (no degradation). Custom scorers without lifecycle metadata receive `COMPLETE` when nothing else applies.
- Configuration: `ai.sentinel.warmup-action` (`ALLOW` | `MONITOR` | `THROTTLE` for legacy). `BLOCK` /
  `QUARANTINE` are rejected by property validation / coerced to `MONITOR` in the engine.

### Migration

| Topic | Guidance |
|-------|----------|
| Default behavior | Cold-start identities now **MONITOR** during warmup instead of **THROTTLE**. |
| Legacy THROTTLE warmup | Set `ai.sentinel.warmup-action=THROTTLE` (not recommended). |
| API | `RiskDecision` gains `evaluationStatuses`. Use `RiskDecision.of(...)` for the previous six-field shape (empty statuses). Binary/source break for positional 6-arg compact construction — only the decision engine constructed it in-tree. |
| Operators | Prefer `mode=MONITOR`; treat `STATISTICAL_WARMUP` as lifecycle, not abuse. |

### Not in this increment

Gated baseline updates (R-040/R-074), relearn (R-041), near-zero variance (R-038), TTL/entropy (R-126/R-127).
