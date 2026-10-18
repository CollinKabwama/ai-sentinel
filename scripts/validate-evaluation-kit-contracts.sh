#!/usr/bin/env bash
# Validate Evaluation Kit JSON Schema contracts and fixtures.
# Uses a workspace-local venv so production runtime deps are unchanged.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="${ROOT}/.venv-eval-kit-validate"
PY="${VENV}/bin/python"
PIP="${VENV}/bin/pip"

if [[ ! -x "${PY}" ]]; then
  python3 -m venv "${VENV}"
fi

if ! "${PY}" -c "import jsonschema, referencing" >/dev/null 2>&1; then
  "${PIP}" install -q 'jsonschema>=4.0'
fi

exec "${PY}" "${ROOT}/scripts/validate-evaluation-kit-contracts.py"
