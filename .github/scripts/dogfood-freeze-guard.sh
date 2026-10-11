#!/usr/bin/env bash
# Dogfood freeze guard (v4 plan 2, F19).
#
# While 4.0 is developed on release/4.0, the live dogfood config and the global process-schema floor stay as
# main has them: they are the 3.x-format files the running server and the plugin hooks read today. There is no
# main->release/4.0 merge sync (3.x fixes land on release/4.0 as parallel twin PRs), so the only exemption is an
# explicit hatch: a head branch named 4.0/sync-main-*, for a twin PR that must mirror a main-side change to
# these files. Use it only for that.
#
# Usage: dogfood-freeze-guard.sh <base-branch> <head-branch>   (changed paths, one per line, on stdin)
#
# Exit 1 with a ::error:: annotation when the base is release/4.0, the head is not a sync branch, and a changed
# path is .taskorchestrator/config.yaml or anything under deploy/global-config/. Exit 0 otherwise (no paths,
# any other base, a sync head, or only other paths).
set -euo pipefail

base="${1:-}"
head="${2:-}"

# An exempt call still drains stdin, so the writer (git diff in CI, under pipefail) never gets SIGPIPE.
drain_and_pass() {
  cat >/dev/null || true
  exit 0
}

if [ "$base" != "release/4.0" ]; then
  drain_and_pass
fi

if [[ "$head" =~ ^4\.0/sync-main-[^[:space:]]+$ ]]; then
  drain_and_pass
fi

frozen=()
while IFS= read -r path || [ -n "$path" ]; do
  path="${path%$'\r'}"
  case "$path" in
    .taskorchestrator/config.yaml | deploy/global-config/*)
      frozen+=("$path")
      ;;
  esac
done

if [ "${#frozen[@]}" -gt 0 ]; then
  echo "::error::'$head' changes the frozen dogfood config while targeting release/4.0: ${frozen[*]}. Only a 4.0/sync-main-* branch (the main->release/4.0 sync) may change .taskorchestrator/config.yaml or deploy/global-config/."
  exit 1
fi
exit 0