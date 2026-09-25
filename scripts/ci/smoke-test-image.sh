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
#      SQLite via JNI, and answer the protocol — not just that the process started.
#
# Usage: scripts/ci/smoke-test-image.sh <image-tag>
#
# Windows/Git Bash note: if running this manually from Git Bash (not the CI runner), set
# MSYS_NO_PATHCONV=1 or run from PowerShell instead — MSYS rewrites leading "/tmp/..." style
# paths passed to `docker exec`/`docker run`, which breaks the readiness-file path comparison.

set -euo pipefail

IMAGE="${1:?Usage: smoke-test-image.sh <image-tag>}"
READINESS_FILE="${READINESS_FILE:-/tmp/mcp-task-orchestrator.ready}"
READY_TIMEOUT_SECS="${READY_TIMEOUT_SECS:-60}"
RUN_ID="$$-${RANDOM:-0}"
VOLUME_NAME="smoke-test-data-${RUN_ID}"
CONTAINER_NAME="smoke-test-${RUN_ID}"
# Named so cleanup can remove it: if `timeout` kills the docker client, `--rm` never fires and
# the container would otherwise keep running.
STDIO_CONTAINER_NAME="smoke-test-stdio-${RUN_ID}"
REQ_FILE=""
OUTPUT_FILE=""

cleanup() {
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  docker rm -f "$STDIO_CONTAINER_NAME" >/dev/null 2>&1 || true
  docker volume rm "$VOLUME_NAME" >/dev/null 2>&1 || true
  [ -n "$REQ_FILE" ] && rm -f "$REQ_FILE"
  [ -n "$OUTPUT_FILE" ] && rm -f "$OUTPUT_FILE"
  return 0
}
trap cleanup EXIT

echo "== smoke-test-image: $IMAGE =="

echo "-- Part 1: readiness probe (\$READINESS_FILE=$READINESS_FILE, timeout=${READY_TIMEOUT_SECS}s) --"
docker volume create "$VOLUME_NAME" >/dev/null
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

docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

echo "-- Part 2: MCP stdio JSON-RPC handshake --"
REQ_FILE="$(mktemp)"
OUTPUT_FILE="$(mktemp)"

cat >"$REQ_FILE" <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke-test","version":"0.0.1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
EOF

set +e
if command -v timeout >/dev/null 2>&1; then
  (cat "$REQ_FILE"; sleep 3) | timeout 30 docker run --rm -i --name "$STDIO_CONTAINER_NAME" -v "${VOLUME_NAME}:/app/data" "$IMAGE" >"$OUTPUT_FILE" 2>&1
else
  (cat "$REQ_FILE"; sleep 3) | docker run --rm -i --name "$STDIO_CONTAINER_NAME" -v "${VOLUME_NAME}:/app/data" "$IMAGE" >"$OUTPUT_FILE" 2>&1
fi
set -e

# The two facts we need are on two different responses: `serverInfo` is only present on the
# initialize response (id=1), and `tools` is only present on the tools/list response (id=2). Scan
# every JSON-looking line in the transcript and take the best value seen for each, rather than
# expecting a single line to carry both.
# Parse with node (present locally and on GitHub runners) rather than jq, and fail loudly if it is
# missing — a silently absent parser would make every run report "handshake incomplete".
if ! command -v node >/dev/null 2>&1; then
  echo "ERROR: node is required to parse the MCP handshake transcript" >&2
  exit 2
fi
read -r HAS_SERVER_INFO TOOLS_COUNT < <(node -e '
  const lines = require("fs").readFileSync(0, "utf8").split(String.fromCharCode(10));
  let si = 0, tc = 0;
  for (const raw of lines) {
    const line = raw.trim();
    if (!line.startsWith("{")) continue;
    let msg; try { msg = JSON.parse(line); } catch { continue; }
    if (msg.id === 1 && msg.result && msg.result.serverInfo) si = 1;
    if (msg.id === 2 && msg.result && Array.isArray(msg.result.tools)) tc = msg.result.tools.length;
  }
  console.log(si + " " + tc);
' <"$OUTPUT_FILE")

if [ "$HAS_SERVER_INFO" -ne 1 ] || [ "$TOOLS_COUNT" -le 0 ]; then
  echo "ERROR: MCP handshake incomplete (serverInfo present=$HAS_SERVER_INFO, tools/list count=$TOOLS_COUNT)" >&2
  echo "---- raw container stdout/stderr ----" >&2
  cat "$OUTPUT_FILE" >&2
  exit 1
fi

echo "initialize returned serverInfo; tools/list returned $TOOLS_COUNT tools."
echo "Smoke test passed."
