#!/usr/bin/env bash
# Reproduce Level-1 Evaluation Kit evidence and verify against declared digests/identities.
#
# Canonical path: host JDK 21 + Maven via scripts/evaluate-generated-corpus.sh.
# Does not upload, fetch remote evidence, modify the repository, or overwrite an
# existing output directory.
set -euo pipefail

CALLER_DIR="$(pwd)"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE_DIR="${ROOT}/evaluation/reproduction"
MANIFEST="${PACKAGE_DIR}/reproduction-manifest.json"
EVALUATE="${ROOT}/scripts/evaluate-generated-corpus.sh"
VERIFY="${ROOT}/scripts/verify-reproduced-evidence.sh"

resolve_path() {
  case "$1" in
    /*) printf '%s' "$1" ;;
    *) printf '%s/%s' "$CALLER_DIR" "$1" ;;
  esac
}

usage() {
  cat <<'EOF'
Usage:
  reproduce-evaluation-evidence.sh --output <new-directory>

Reproduces the Level-1 kit-reference targets declared in
evaluation/reproduction/reproduction-manifest.json, writes Kit evidence under
the output directory, generates a local index.html, and verifies digests and
identities.

Prerequisites (host canonical path):
  - JDK 21 on PATH (JAVA_HOME recommended)
  - Maven (mvn) on PATH
  - python3 on PATH

The output directory must not already exist.
EOF
}

OUTPUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --output)
      shift
      if [[ $# -eq 0 || "$1" == -* ]]; then
        echo "ERROR: --output requires a directory path" >&2
        exit 2
      fi
      OUTPUT="$(resolve_path "$1")"
      shift
      ;;
    *)
      echo "ERROR: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "${OUTPUT}" ]]; then
  echo "ERROR: --output is required" >&2
  usage >&2
  exit 2
fi

if [[ -e "${OUTPUT}" ]]; then
  echo "ERROR: output directory already exists (refusing overwrite): ${OUTPUT}" >&2
  exit 1
fi

if [[ ! -f "${MANIFEST}" ]]; then
  echo "ERROR: reproduction manifest not found: ${MANIFEST}" >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "ERROR: java not found on PATH. JDK 21 is required for the host reproduction path." >&2
  exit 1
fi

if ! java -version 2>&1 | head -n 1 | grep -Eq 'version "21(\.|")'; then
  echo "ERROR: JDK 21 is required. Detected: $(java -version 2>&1 | head -n 1)" >&2
  exit 1
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "ERROR: mvn not found on PATH. Maven is required for the host reproduction path." >&2
  exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 not found on PATH." >&2
  exit 1
fi

mkdir -p "${OUTPUT}"

THRESHOLD="$(python3 -c "import json; from pathlib import Path; print(json.loads(Path(r'''${MANIFEST}''').read_text(encoding='utf-8'))['anomalyThreshold'])")"

echo "AI-Sentinel Evidence Reproduction"
echo "Package: kit-reference-level1-v1"
echo "Threshold: ${THRESHOLD}"
echo "Output: ${OUTPUT}"
echo

python3 - <<PY
import json
import subprocess
import sys
from pathlib import Path

root = Path(r"""${ROOT}""")
manifest_path = Path(r"""${MANIFEST}""")
output = Path(r"""${OUTPUT}""")
evaluate = Path(r"""${EVALUATE}""")
threshold = r"""${THRESHOLD}"""

package = json.loads(manifest_path.read_text(encoding="utf-8"))
for target in package["targets"]:
    target_id = target["targetId"]
    corpus_rel = target["corpusPath"]
    corpus_abs = root / corpus_rel
    target_out = output / target_id
    print(f"Reproducing {target_id} from {corpus_rel}")
    if not corpus_abs.is_dir():
        print(f"ERROR: corpus directory not found: {corpus_abs}", file=sys.stderr)
        sys.exit(1)
    completed = subprocess.run(
        [
            str(evaluate),
            "--corpus",
            str(corpus_abs),
            "--threshold",
            str(threshold),
            "--output",
            str(target_out),
        ],
        check=False,
    )
    if completed.returncode != 0:
        sys.exit(completed.returncode)

rows = []
for target in package["targets"]:
    target_id = target["targetId"]
    report = f"{target_id}/evaluation-report.html"
    rows.append(
        f'<li><a href="{report}"><code>{target_id}</code></a> — evaluation report</li>'
    )
html = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AI-Sentinel evidence reproduction</title>
<style>
body{font:15px/1.5 system-ui,sans-serif;max-width:820px;margin:2rem auto;padding:0 1rem;color:#172033}
code{word-break:break-all}.note{background:#f4f7fb;border:1px solid #d7dde3;padding:.75rem 1rem}
</style>
</head>
<body>
<h1>AI-Sentinel evidence reproduction</h1>
<p>Local landing page for Level-1 repository-controlled Evaluation Kit evidence.</p>
<ul>
""" + "\n".join(rows) + """
</ul>
<p class="note">Machine verification is authoritative via <code>reproduction-result.json</code>.
HTML is for interpretation only. Reproduction PASS means declared repository-controlled
evidence was regenerated and matched digests/identities. It does not establish production
efficacy, external-dataset validity, or deployment readiness.</p>
</body>
</html>
"""
(output / "index.html").write_text(html, encoding="utf-8")
print(f"Wrote {output / 'index.html'}")
PY

echo
echo "Verifying reproduced evidence..."
set +e
"${VERIFY}" --manifest "${MANIFEST}" --results "${OUTPUT}" --repo-root "${ROOT}"
VERIFY_STATUS=$?
set -e

echo
if [[ "${VERIFY_STATUS}" -eq 0 ]]; then
  cat <<EOF
Meaning:
  Repository-controlled evaluation evidence reproduced and matched declared
  reference artifacts and identities.

Not established:
  Production efficacy, external dataset validity, deployment readiness,
  cryptographic attestation, or independent outsider execution by a third party.

Inspect HTML:
  ${OUTPUT}/index.html
EOF
else
  echo "Reproduction verification FAILED. See messages above."
fi

exit "${VERIFY_STATUS}"
