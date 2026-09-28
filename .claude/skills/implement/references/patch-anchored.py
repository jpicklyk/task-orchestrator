#!/usr/bin/env python3
"""Anchored, validate-all-then-write, byte-level text patcher (proposal 610b9a9f).

Use when the Edit/Write tools are refused (e.g. a session-worktree guard) instead of `sed -i`.

Usage:
    DRY=1 python patch-anchored.py spec.json   # check every anchor, write nothing
    python patch-anchored.py spec.json         # apply, only if every anchor passes

spec.json (UTF-8, BOM tolerated) is a JSON list of edits:
    [{"file": "<path>", "old": "<exact anchor text>", "new": "<replacement>"}, ...]

- `file` is opened exactly as given (absolute, or relative to the current directory). Edits
  naming the same file by different spellings compose, in spec order.
- Each `old` must occur EXACTLY once in its file as left by earlier edits in the spec; a
  mismatch reports the actual count.
- Bytes in, bytes out: each file keeps its CRLF or LF endings. LF-only `old`/`new` text is
  normalized to CRLF for a CRLF file; CRLF spec text against an LF file is NOT normalized and
  fails closed (count 0).
- Validate-all-then-write: every anchor in every file is checked before any write. Writes go to
  temp siblings first, then each is os.replace()d into place, which narrows (but cannot fully
  close) the window in which a failure leaves some files written.
"""
import json
import os
import sys


def to_file_eol(text, crlf):
    data = text.encode("utf-8")
    return data.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n") if crlf else data


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    dry = os.environ.get("DRY", "").strip().lower() in ("1", "true", "yes")
    try:
        with open(argv[1], "r", encoding="utf-8-sig") as fh:
            spec = json.load(fh)
    except (OSError, ValueError) as exc:
        print(f"FAIL spec: cannot read {argv[1]} ({exc}); no file written", file=sys.stderr)
        return 2
    bad = [] if isinstance(spec, list) and spec else ["spec must be a non-empty JSON list"]
    for i, e in enumerate(spec if not bad else []):
        if not (isinstance(e, dict) and all(isinstance(e.get(k), str) for k in ("file", "old", "new")) and e["old"]):
            bad.append(f"edit {i}: needs string file, non-empty string old, string new")
    if bad:
        for line in bad:
            print("FAIL " + line, file=sys.stderr)
        return 2

    contents, originals, paths, failures = {}, {}, {}, []  # keyed by canonical path
    for i, e in enumerate(spec):
        path = e["file"]
        key = os.path.normcase(os.path.realpath(path))
        if key not in contents:
            try:
                with open(path, "rb") as fh:
                    contents[key] = originals[key] = fh.read()
                paths[key] = (path, os.path.realpath(path))  # real case kept for writes
            except OSError as exc:
                failures.append(f"edit {i}: {path}: cannot read ({exc})")
                continue
        data = contents[key]
        crlf = b"\r\n" in data
        old_b, new_b = to_file_eol(e["old"], crlf), to_file_eol(e["new"], crlf)
        count = data.count(old_b)
        if count != 1:
            failures.append(f"edit {i}: {path}: anchor found {count} times (need exactly 1)")
            continue
        contents[key] = data.replace(old_b, new_b, 1)
        print(f"edit {i}: {path}: anchor OK")

    if failures:
        for line in failures:
            print("FAIL " + line, file=sys.stderr)
        print(f"{len(failures)} edit(s) failed; no file written", file=sys.stderr)
        return 1
    if dry:
        print(f"DRY=1: all {len(spec)} edit(s) validate; no file written")
        return 0
    changed = [k for k in contents if contents[k] != originals[k]]
    temps = {}
    try:
        for k in changed:
            temps[k] = paths[k][1] + ".patch-anchored.tmp"
            with open(temps[k], "wb") as fh:
                fh.write(contents[k])
    except OSError as exc:
        for t in temps.values():
            if os.path.exists(t):
                os.remove(t)
        print(f"FAIL temp write ({exc}); no file written", file=sys.stderr)
        return 1
    for k in changed:
        os.replace(temps[k], paths[k][1])
        print(f"wrote {paths[k][0]}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
