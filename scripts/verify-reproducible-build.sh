#!/usr/bin/env bash
# Verify that clean Maven builds of the published library modules produce
# byte-identical JARs (artifact reproducibility).
#
# Usage:
#   ./scripts/verify-reproducible-build.sh
#
# Optional environment:
#   AI_SENTINEL_REPRO_JDK_HOME  — override JAVA_HOME for the builds
#   AI_SENTINEL_REPRO_KEEP=1    — keep temporary directories for inspection
#
# Does not publish, sign, or install to a remote repository.
# Does not compare against Maven Central (historical Central artifacts may differ).
#
# Distinct from Evaluation Kit / corpus determinism verification.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULES=(ai-sentinel-core ai-sentinel-spring-boot-starter)

if [[ -n "${AI_SENTINEL_REPRO_JDK_HOME:-}" ]]; then
  export JAVA_HOME="${AI_SENTINEL_REPRO_JDK_HOME}"
elif [[ -z "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

if ! command -v mvn >/dev/null 2>&1; then
  echo "FAIL: mvn not found on PATH" >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "FAIL: java not found on PATH (need JDK 21)" >&2
  exit 1
fi

sha256_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/aisentinel-repro.XXXXXX")"
# Intentionally different path lengths / prefixes to surface absolute-path leakage.
DIR_A="${TMP_ROOT}/build-A"
DIR_B="${TMP_ROOT}/very-different-path/build-B"
cleanup() {
  if [[ "${AI_SENTINEL_REPRO_KEEP:-0}" == "1" ]]; then
    echo "Kept temporary directories under: ${TMP_ROOT}" >&2
  else
    rm -rf "${TMP_ROOT}"
  fi
}
trap cleanup EXIT

echo "Artifact reproducibility verification"
echo "  source: ${ROOT}"
echo "  java:   $(java -version 2>&1 | head -1)"
echo "  maven:  $(mvn -version 2>&1 | head -1)"
echo "  temp:   ${TMP_ROOT}"

mkdir -p "${DIR_A}" "${DIR_B}"

# Copy tracked paths from the working tree (includes uncommitted edits to tracked
# files). This verifies the tree under test, not only the last commit.
copy_tracked_tree() {
  local dest="$1"
  mkdir -p "${dest}"
  (
    cd "${ROOT}"
    git ls-files -z
  ) | while IFS= read -r -d '' rel; do
    local src="${ROOT}/${rel}"
    local dst="${dest}/${rel}"
    mkdir -p "$(dirname "${dst}")"
    # Skip missing working-tree paths (deleted-but-tracked).
    if [[ -f "${src}" || -L "${src}" ]]; then
      cp -p "${src}" "${dst}"
    elif [[ -d "${src}" ]]; then
      mkdir -p "${dst}"
    fi
  done
}

copy_tracked_tree "${DIR_A}"
copy_tracked_tree "${DIR_B}"

build_one() {
  local work="$1"
  local label="$2"
  local m2="${work}/.m2-local"
  mkdir -p "${m2}"
  echo "Building ${label} in ${work} ..."
  (
    cd "${work}"
    mvn -B -q clean package -DskipTests \
      -pl ai-sentinel-core,ai-sentinel-spring-boot-starter -am \
      -Dmaven.repo.local="${m2}"
  )
}

build_one "${DIR_A}" "A"
build_one "${DIR_B}" "B"

FAIL=0
echo
echo "JAR SHA-256 comparison"
printf '%-42s %-64s %-64s %s\n' "module" "build-A" "build-B" "result"
for module in "${MODULES[@]}"; do
  jar_a="${DIR_A}/${module}/target/${module}-"*.jar
  # Resolve single main jar (exclude sources/javadoc if present)
  jar_a="$(ls "${DIR_A}/${module}/target/${module}"-*.jar 2>/dev/null | grep -vE '(-sources|-javadoc)\.jar$' | head -1 || true)"
  jar_b="$(ls "${DIR_B}/${module}/target/${module}"-*.jar 2>/dev/null | grep -vE '(-sources|-javadoc)\.jar$' | head -1 || true)"
  if [[ -z "${jar_a}" || -z "${jar_b}" || ! -f "${jar_a}" || ! -f "${jar_b}" ]]; then
    printf '%-42s %s\n' "${module}" "FAIL (jar missing)"
    FAIL=1
    continue
  fi
  sha_a="$(sha256_file "${jar_a}")"
  sha_b="$(sha256_file "${jar_b}")"
  if [[ "${sha_a}" == "${sha_b}" ]]; then
    result="MATCH"
  else
    result="DIFFER"
    FAIL=1
  fi
  printf '%-42s %-64s %-64s %s\n' "${module}" "${sha_a}" "${sha_b}" "${result}"
done

# Parent packaging is pom-only; confirm POM bytes match across copies.
pom_a="$(sha256_file "${DIR_A}/pom.xml")"
pom_b="$(sha256_file "${DIR_B}/pom.xml")"
if [[ "${pom_a}" == "${pom_b}" ]]; then
  echo "parent pom.xml (source): MATCH (${pom_a})"
else
  echo "parent pom.xml (source): DIFFER"
  FAIL=1
fi

echo
if [[ "${FAIL}" -eq 0 ]]; then
  echo "PASS: published module JARs are byte-identical across independent clean builds"
  exit 0
fi
echo "FAIL: published module JARs are not byte-identical across independent clean builds" >&2
exit 1
