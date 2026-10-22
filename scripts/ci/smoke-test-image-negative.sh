#!/usr/bin/env bash
# Negative path for the upgrade smoke: an EMPTY seed must make smoke-test-image.sh fail.
#
# An empty file is the shape of a brand-new database. It boots cleanly (Part 1 and Part 2 pass), but
# an upgrade can neither have migrated its rows nor taken a pre-4.0 backup. So the Part 3 checks must
# fail, and they must fail for BOTH reasons in the same run (the assertions accumulate; none is an
# early exit):
#   - work_items has 0 rows where the golden manifest expects count.work_items
#   - zero pre-4.0 .bak files where exactly one is expected
# If the positive smoke ever passes on an empty seed, its assertions have gone vacuous.
#
# Usage: scripts/ci/smoke-test-image-negative.sh <image-tag>
# Needs the same tools as smoke-test-image.sh (docker, host sqlite3, node). Linux CI only.
# Exit codes: 0 = the smoke failed the way it must; 1 = it did not; 2 = usage or setup error.

set -uo pipefail

if [ "$#" -ne 1 ] || [ -z "$1" ]; then
    echo "Usage: $0 <image-tag>" >&2
    exit 2
fi
IMAGE="$1"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SMOKE="$SCRIPT_DIR/smoke-test-image.sh"
MANIFEST="$REPO_ROOT/current/src/test/resources/upgrade/golden-v17-3.16.manifest.txt"

if [ ! -f "$SMOKE" ]; then
    echo "ERROR: smoke script not found: $SMOKE" >&2
    exit 2
fi
if [ ! -f "$MANIFEST" ]; then
    echo "ERROR: golden manifest not found: $MANIFEST" >&2
    exit 2
fi

EXPECT_WORK_ITEMS="$(sed -n 's/^count\.work_items=//p' "$MANIFEST" | head -n 1 | tr -d '\r')"
if [ -z "$EXPECT_WORK_ITEMS" ]; then
    echo "ERROR: count.work_items missing from $MANIFEST" >&2
    exit 2
fi

TMP_DIR="$(mktemp -d)"
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

SEED="$TMP_DIR/empty-seed.sqlite"
OUT="$TMP_DIR/smoke-output.txt"
: > "$SEED"
if [ -s "$SEED" ]; then
    echo "ERROR: the empty seed is not empty" >&2
    exit 2
fi

echo "-- Negative path: empty seed must fail the upgrade smoke (image: $IMAGE) --"
"$SMOKE" --seed "$SEED" "$IMAGE" > "$OUT" 2>&1
SMOKE_EXIT=$?

FAIL=0

expect_output() {
    # $1 = fixed string that must appear in the smoke output; $2 = why it matters
    if grep -qF -- "$1" "$OUT"; then
        echo "ok: output has: $1"
    else
        echo "ERROR: expected the empty-seed smoke output to contain: $1 ($2)" >&2
        FAIL=1
    fi
}

if [ "$SMOKE_EXIT" -eq 0 ]; then
    echo "ERROR: smoke-test-image.sh exited 0 on an empty seed; it must fail" >&2
    FAIL=1
else
    echo "ok: smoke exited non-zero ($SMOKE_EXIT)"
fi

# The failure must come from the Part 3 database checks, not from the boot.
expect_output "Readiness marker found." "the container must boot, so the failure is not a boot failure"
expect_output "stdout is pure JSON-RPC" "the stdio handshake must pass, so the failure is not a protocol failure"
# Both Part 3 failures in one run: the checks accumulate rather than exit early.
expect_output "ERROR: work_items has 0 rows, golden manifest expects '$EXPECT_WORK_ITEMS'" "row-count check"
expect_output "ERROR: expected exactly one pre-4.0 .bak after the readiness and stdio boots, found 0" "backup count check"

if grep -qF -- "Smoke test passed" "$OUT"; then
    echo "ERROR: the empty-seed smoke printed a pass line" >&2
    FAIL=1
else
    echo "ok: output has no pass line"
fi

if [ "$FAIL" -ne 0 ]; then
    echo "---- smoke output (empty seed) ----" >&2
    cat "$OUT" >&2
    echo "---- end smoke output ----" >&2
    echo "Negative path FAILED: the upgrade smoke did not reject an empty seed as required." >&2
    exit 1
fi

echo "Negative path passed: the empty seed failed the upgrade smoke for both expected reasons."
