#!/usr/bin/env bash
# Verify a finalized MONITOR-mode pilot evidence directory.
# Usage: ./scripts/verify-monitor-pilot-evidence.sh <pilot-directory>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "${ROOT}/scripts/verify_monitor_pilot_evidence.py" "$@"
