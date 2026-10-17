#!/usr/bin/env bash
# Smoke-tests a built task-orchestrator Docker image before it is trusted enough to push/publish.
#
# Two independent checks, both against a container started FROM the image under test (never a
# pulled tag), each cleaned up before the other starts:
#
#   1. Readiness probe: start the container detached, poll for the $READINESS_FILE marker the
#      server touches once startup (DB init, schema update, transport bind) has fully succeeded.
#      A container that never becomes ready (bad CMD, crash loop, missing native deps) fails here
#      with the container's logs dumped for diagnosis.
#
#   2. MCP stdio handshake: pipe a minimal initialize -> notifications/initialized -> tools/list
#      JSON-RPC sequence into a fresh `docker run --rm -i` of the same image and assert the
#      tools/list response (id=2) carries a non-empty `result.tools` array and a `result.serverInfo`.
#      This proves the actual runtime JVM (Corretto 25 in runtime-current) can load the jar, open
#      SQLite via JNI, and answer the protocol — not just that the process started. stdout and
#      stderr are captured SEPARATELY (never merged): every non-blank stdout line MUST be a JSON
#      object with "jsonrpc":"2.0" — anything else (a stray banner some dependency prints straight
#      to System.out, a stack trace, plain text) FAILS the check, since it would corrupt the
#      stdio JSON-RPC stream for a real client. stderr is captured for diagnostics only and is
#      never checked for purity (INFO/WARN/ERROR logs are expected there).
#
#   3. Upgrade checks (only with --seed): see "Upgrade smoke" below.
#
# Upgrade smoke (--seed <sqlite>): the volume is pre-seeded with the given database file (the
# 3.16 golden volume in CI) before the image under test first touches it, so Parts 1-2 boot the
# image against an OLD database and migrate it. The container is stopped gracefully (SIGTERM, so
# SQLite closes and checkpoints the WAL) and the volume is extracted and inspected with host
# sqlite3:
#   Part 3: data_steps holds the once-steps and no server-config-import row (every-boot steps are
#           never recorded); the highest successful flyway version equals the highest V<N>__ file in
#           the checkout, with zero failed rows; row counts equal the golden manifest count.* lines
#           (an empty-volume swap would go red). The two boots before Part 3 (readiness, stdio)
#           must have left exactly one pre-4.0 backup (*.bak, current-tasks.db.pre-4.0.<ts>.bak)
#           in the volume, and that copy must verify: PRAGMA integrity_check = ok and the highest
#           successful flyway version equal to the golden manifest's schema_version.
#   Part 4: boot the same volume once more and assert every data_steps.applied_at is unchanged
#           (once-steps are skipped when already applied) and that the volume still holds exactly
#           that one backup, so a later boot never takes a second copy.
#
# Usage: scripts/ci/smoke-test-image.sh [--seed <sqlite>] <image-tag>
#   Without --seed the behaviour is the fresh-volume smoke (Parts 1 and 2) only.
#
# Windows/Git Bash note: if running this manually from Git Bash (not the CI runner), set
# MSYS_NO_PATHCONV=1 or run from PowerShell instead — MSYS rewrites leading "/tmp/..." style
# paths passed to `docker exec`/`docker run`, which breaks the readiness-file path comparison.

set -euo pipefail

SEED=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --seed)
      SEED="${2:?Usage: smoke-test-image.sh [--seed <sqlite>] <image-tag>}"
      shift 2
      ;;
    *)
      break
      ;;
  esac
done
IMAGE="${1:?Usage: smoke-test-image.sh [--seed <sqlite>] <image-tag>}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIGRATION_DIR="${REPO_ROOT}/current/src/main/resources/db/migration/sqlite"
GOLDEN_MANIFEST="${REPO_ROOT}/current/src/test/resources/upgrade/golden-v17-3.16.manifest.txt"
if [ -n "$SEED" ] && [ ! -f "$SEED" ]; then
  echo "ERROR: seed file not found: $SEED" >&2
  exit 1
fi
READINESS_FILE="${READINESS_FILE:-/tmp/mcp-task-orchestrator.ready}"
READY_TIMEOUT_SECS="${READY_TIMEOUT_SECS:-60}"
RUN_ID="$$-${RANDOM:-0}"
VOLUME_NAME="smoke-test-data-${RUN_ID}"
CONTAINER_NAME="smoke-test-${RUN_ID}"
# Named so cleanup can remove it: if `timeout` kills the docker client, `--rm` never fires and
# the container would otherwise keep running.
STDIO_CONTAINER_NAME="smoke-test-stdio-${RUN_ID}"
EXTRACT_CONTAINER_NAME="smoke-test-extract-${RUN_ID}"
REQ_FILE=""
OUTPUT_FILE=""
STDERR_FILE=""
EXTRACT_DIR=""

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  docker rm -f "$STDIO_CONTAINER_NAME" >/dev/null 2>&1 || true
  docker rm -f "$EXTRACT_CONTAINER_NAME" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME_NAME" >/dev/null 2>&1 || true
  [ -n "$REQ_FILE" ] && rm -f "$REQ_FILE"
  [ -n "$OUTPUT_FILE" ] && rm -f "$OUTPUT_FILE"
  [ -n "$STDERR_FILE" ] && rm -f "$STDERR_FILE"
  [ -n "$EXTRACT_DIR" ] && rm -rf "$EXTRACT_DIR"
  return 0
}
trap cleanup EXIT

echo "== smoke-test-image: $IMAGE =="

echo "-- Part 1: readiness probe (\$READINESS_FILE=$READINESS_FILE, timeout=${READY_TIMEOUT_SECS}s) --"
docker volume create "$VOLUME_NAME" >/dev/null

if [ -n "$SEED" ]; then
  if ! command -v sqlite3 >/dev/null 2>&1; then
    echo "ERROR: sqlite3 is required to inspect the upgraded database (--seed)" >&2
    exit 2
  fi
  SEED_ABS="$(cd "$(dirname "$SEED")" && pwd)/$(basename "$SEED")"
  echo "Seeding volume $VOLUME_NAME from $SEED_ABS"
  # The server runs as uid 1001; a root-owned database file would open read-only and fail startup.
  docker run --rm --user 0 -v "${VOLUME_NAME}:/app/data" -v "${SEED_ABS}:/seed.sqlite:ro" "$IMAGE" \
    sh -c 'cp /seed.sqlite /app/data/current-tasks.db && chown 1001:1001 /app/data/current-tasks.db'
fi

docker run -d -i --name "$CONTAINER_NAME" -v "${VOLUME_NAME}:/app/data" "$IMAGE" >/dev/null

READY=0
for _ in $(seq 1 "$READY_TIMEOUT_SECS"); do
  if docker exec "$CONTAINER_NAME" test -f "$READINESS_FILE" 2>/dev/null; then
    READY=1
    break
  fi
  sleep 1
done

if [ "$READY" -ne 1 ]; then
  echo "ERROR: readiness marker $READINESS_FILE not present after ${READY_TIMEOUT_SECS}s" >&2
  echo "---- docker logs $CONTAINER_NAME ----" >&2
  docker logs "$CONTAINER_NAME" >&2 || true
  exit 1
fi
echo "Readiness marker found."

if [ -n "$SEED" ]; then
  # Graceful stop (SIGTERM) so SQLite closes and checkpoints the WAL before the volume is read.
  docker stop "$CONTAINER_NAME" >/dev/null
fi
docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

echo "-- Part 2: MCP stdio JSON-RPC handshake --"
REQ_FILE="$(mktemp)"
OUTPUT_FILE="$(mktemp)"
STDERR_FILE="$(mktemp)"

cat >"$REQ_FILE" <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke-test","version":"0.0.1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
EOF

set +e
if command -v timeout >/dev/null 2>&1; then
  (cat "$REQ_FILE"; sleep 3) | timeout 30 docker run --rm -i --name "$STDIO_CONTAINER_NAME" -v "${VOLUME_NAME}:/app/data" "$IMAGE" >"$OUTPUT_FILE" 2>"$STDERR_FILE"
else
  (cat "$REQ_FILE"; sleep 3) | docker run --rm -i --name "$STDIO_CONTAINER_NAME" -v "${VOLUME_NAME}:/app/data" "$IMAGE" >"$OUTPUT_FILE" 2>"$STDERR_FILE"
fi
set -e

# The two facts we need are on two different responses: `serverInfo` is only present on the
# initialize response (id=1), and `tools` is only present on the tools/list response (id=2). Scan
# every JSON-looking line in the transcript and take the best value seen for each, rather than
# expecting a single line to carry both.
#
# stdout purity: every non-blank line of $OUTPUT_FILE (stdout ONLY — stderr is a separate file and
# never enters this check) must be a JSON object with "jsonrpc":"2.0". A stray banner, stack
# trace, or any other non-JSON-RPC text on stdout corrupts the protocol stream for a real client
# and fails the check even if the handshake itself otherwise looks complete.
#
# Parse with node (present locally and on GitHub runners) rather than jq, and fail loudly if it is
# missing — a silently absent parser would make every run report "handshake incomplete".
if ! command -v node >/dev/null 2>&1; then
  echo "ERROR: node is required to parse the MCP handshake transcript" >&2
  exit 2
fi
read -r HAS_SERVER_INFO TOOLS_COUNT IMPURE_LINE < <(node -e '
  const lines = require("fs").readFileSync(0, "utf8").split(String.fromCharCode(10));
  let si = 0, tc = 0, impure = "";
  for (const raw of lines) {
    const line = raw.trim();
    if (line === "") continue;
    let msg;
    try { msg = JSON.parse(line); } catch { impure = impure || line; continue; }
    if (typeof msg !== "object" || msg === null || Array.isArray(msg) || msg.jsonrpc !== "2.0") {
      impure = impure || line;
      continue;
    }
    if (msg.id === 1 && msg.result && msg.result.serverInfo) si = 1;
    if (msg.id === 2 && msg.result && Array.isArray(msg.result.tools)) tc = msg.result.tools.length;
  }
  const encodedImpure = impure ? Buffer.from(impure, "utf8").toString("base64") : "-";
  console.log(si + " " + tc + " " + encodedImpure);
' <"$OUTPUT_FILE")

FAIL=0
if [ "$IMPURE_LINE" != "-" ]; then
  DECODED_IMPURE="$(printf '%s' "$IMPURE_LINE" | base64 -d 2>/dev/null || echo "$IMPURE_LINE")"
  echo "ERROR: stdout is not pure JSON-RPC — found a non-JSON-RPC line: $DECODED_IMPURE" >&2
  FAIL=1
fi
if [ "$HAS_SERVER_INFO" -ne 1 ] || [ "$TOOLS_COUNT" -le 0 ]; then
  echo "ERROR: MCP handshake incomplete (serverInfo present=$HAS_SERVER_INFO, tools/list count=$TOOLS_COUNT)" >&2
  FAIL=1
fi

if [ "$FAIL" -ne 0 ]; then
  echo "---- container stdout ----" >&2
  cat "$OUTPUT_FILE" >&2
  echo "---- container stderr ----" >&2
  cat "$STDERR_FILE" >&2
  exit 1
fi

echo "initialize returned serverInfo; tools/list returned $TOOLS_COUNT tools."
echo "stdout is pure JSON-RPC (no stray banners/text)."

if [ -z "$SEED" ]; then
  echo "Smoke test passed."
  exit 0
fi

# Copies the volume's /app/data (db + -wal + -shm) into a fresh temp dir and prints its path.
extract_volume() {
  local dir
  dir="$(mktemp -d)"
  # The runtime image has no tar, so copy out of a created (never started) container instead.
  docker create --name "$EXTRACT_CONTAINER_NAME" -v "${VOLUME_NAME}:/app/data" "$IMAGE" >/dev/null
  docker cp "${EXTRACT_CONTAINER_NAME}:/app/data/." "$dir"
  docker rm -f "$EXTRACT_CONTAINER_NAME" >/dev/null 2>&1 || true
  echo "$dir"
}

echo "-- Part 3: upgraded database checks (seed: $SEED) --"
EXTRACT_DIR="$(extract_volume)"
DB="${EXTRACT_DIR}/current-tasks.db"
UP_FAIL=0

STEPS="$(sqlite3 "$DB" 'SELECT name FROM data_steps ORDER BY name')"
for step in config-canonicalize type-backfill traits-from-properties pin-backfill; do
  if ! printf '%s\n' "$STEPS" | grep -qx "$step"; then
    echo "ERROR: data_steps has no row for $step (have: $(printf '%s' "$STEPS" | tr '\n' ' '))" >&2
    UP_FAIL=1
  fi
done
if printf '%s\n' "$STEPS" | grep -qx "server-config-import"; then
  echo "ERROR: data_steps holds a server-config-import row (every-boot steps must never be recorded)" >&2
  UP_FAIL=1
fi

EXPECT_VERSION="$(ls "$MIGRATION_DIR" | sed -n 's/^V\([0-9][0-9]*\)__.*$/\1/p' | sort -n | tail -1)"
ACTUAL_VERSION="$(sqlite3 "$DB" 'SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success=1')"
FAILED_ROWS="$(sqlite3 "$DB" 'SELECT count(*) FROM flyway_schema_history WHERE success=0')"
if [ -z "$EXPECT_VERSION" ] || [ "$ACTUAL_VERSION" != "$EXPECT_VERSION" ]; then
  echo "ERROR: flyway max successful version is '$ACTUAL_VERSION', expected '$EXPECT_VERSION' (newest V<N>__ in the checkout)" >&2
  UP_FAIL=1
fi
if [ "$FAILED_ROWS" != "0" ]; then
  echo "ERROR: flyway_schema_history has $FAILED_ROWS failed (success=0) rows" >&2
  UP_FAIL=1
fi

for table in work_items notes dependencies; do
  EXPECT_COUNT="$(tr -d '\r' <"$GOLDEN_MANIFEST" | sed -n "s/^count\.${table}=\([0-9][0-9]*\)$/\1/p")"
  ACTUAL_COUNT="$(sqlite3 "$DB" "SELECT count(*) FROM ${table}")"
  if [ -z "$EXPECT_COUNT" ] || [ "$ACTUAL_COUNT" != "$EXPECT_COUNT" ]; then
    echo "ERROR: $table has $ACTUAL_COUNT rows, golden manifest expects '$EXPECT_COUNT'" >&2
    UP_FAIL=1
  fi
done

# Pre-upgrade backup: the first boot against the old database copies it aside exactly once.
# Count every *.bak first, then check the strict name, so a stray-named copy is caught too. The
# copy is verified here (never the checked-in seed, which must not be opened with sqlite3).
BAK_NAMES="$(find "$EXTRACT_DIR" -maxdepth 1 -type f -name '*.bak' -exec basename {} \; | sort)"
BAK_COUNT="$(printf '%s' "$BAK_NAMES" | grep -c . || true)"
FIRST_BAK=""
if [ "$BAK_COUNT" -ne 1 ]; then
  echo "ERROR: expected exactly one pre-4.0 .bak after the first boot, found ${BAK_COUNT}: $(if [ -n "$BAK_NAMES" ]; then printf '%s' "$BAK_NAMES" | tr '\n' ' '; else printf '(none)'; fi)" >&2
  UP_FAIL=1
else
  FIRST_BAK="$BAK_NAMES"
  if ! printf '%s\n' "$FIRST_BAK" | grep -Eq '^current-tasks\.db\.pre-4\.0\.[0-9]{8}T[0-9]{6}Z\.bak$'; then
    echo "ERROR: unexpected .bak file name $FIRST_BAK" >&2
    UP_FAIL=1
  else
    BAK_DB="${EXTRACT_DIR}/${FIRST_BAK}"
    BAK_EXPECT_VERSION="$(tr -d '\r' <"$GOLDEN_MANIFEST" | sed -n 's/^schema_version=\([0-9][0-9]*\)$/\1/p')"
    BAK_INTEGRITY="$(sqlite3 "$BAK_DB" 'PRAGMA integrity_check' 2>&1 | head -1 || true)"
    BAK_VERSION="$(sqlite3 "$BAK_DB" 'SELECT MAX(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success=1' 2>&1 | head -1 || true)"
    if [ "$BAK_INTEGRITY" != "ok" ]; then
      echo "ERROR: pre-4.0 .bak $FIRST_BAK failed verification: integrity_check returned '$BAK_INTEGRITY'" >&2
      UP_FAIL=1
    elif [ -z "$BAK_EXPECT_VERSION" ] || [ "$BAK_VERSION" != "$BAK_EXPECT_VERSION" ]; then
      echo "ERROR: pre-4.0 .bak $FIRST_BAK failed verification: flyway max successful version is '$BAK_VERSION', golden manifest schema_version is '$BAK_EXPECT_VERSION'" >&2
      UP_FAIL=1
    else
      echo "pre-4.0 backup verified: $FIRST_BAK (schema version $BAK_VERSION)"
    fi
  fi
fi

if [ "$UP_FAIL" -ne 0 ]; then
  exit 1
fi
FIRST_STEPS="$(sqlite3 "$DB" "SELECT name || '=' || applied_at FROM data_steps ORDER BY name")"
echo "data_steps recorded, flyway at V${ACTUAL_VERSION}, golden row counts preserved."

echo "-- Part 4: second boot is idempotent (applied_at unchanged) --"
rm -rf "$EXTRACT_DIR"
EXTRACT_DIR=""
docker run -d -i --name "$CONTAINER_NAME" -v "${VOLUME_NAME}:/app/data" "$IMAGE" >/dev/null
READY=0
for _ in $(seq 1 "$READY_TIMEOUT_SECS"); do
  if docker exec "$CONTAINER_NAME" test -f "$READINESS_FILE" 2>/dev/null; then
    READY=1
    break
  fi
  sleep 1
done
if [ "$READY" -ne 1 ]; then
  echo "ERROR: second boot never became ready after ${READY_TIMEOUT_SECS}s" >&2
  docker logs "$CONTAINER_NAME" >&2 || true
  exit 1
fi
docker stop "$CONTAINER_NAME" >/dev/null
docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
EXTRACT_DIR="$(extract_volume)"
SECOND_STEPS="$(sqlite3 "${EXTRACT_DIR}/current-tasks.db" "SELECT name || '=' || applied_at FROM data_steps ORDER BY name")"
if [ "$FIRST_STEPS" != "$SECOND_STEPS" ]; then
  echo "ERROR: data_steps.applied_at changed across a second boot (once-steps re-ran)" >&2
  printf 'first:\n%s\nsecond:\n%s\n' "$FIRST_STEPS" "$SECOND_STEPS" >&2
  exit 1
fi
echo "second boot reached readiness; data_steps unchanged."
SECOND_BAK_NAMES="$(find "$EXTRACT_DIR" -maxdepth 1 -type f -name '*.bak' -exec basename {} \; | sort)"
SECOND_BAK_COUNT="$(printf '%s' "$SECOND_BAK_NAMES" | grep -c . || true)"
if [ "$SECOND_BAK_COUNT" -ne 1 ] || [ "$SECOND_BAK_NAMES" != "$FIRST_BAK" ]; then
  echo "ERROR: expected the same single pre-4.0 .bak after the second boot (${FIRST_BAK}), found ${SECOND_BAK_COUNT}: $(if [ -n "$SECOND_BAK_NAMES" ]; then printf '%s' "$SECOND_BAK_NAMES" | tr '\n' ' '; else printf '(none)'; fi)" >&2
  exit 1
fi
echo "second boot took no new backup: $FIRST_BAK"
echo "Smoke test passed (upgrade from seed)."
