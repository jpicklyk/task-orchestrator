#!/usr/bin/env python3
"""Anchored, all-or-nothing, byte-level text patcher (proposal 610b9a9f).

Use when the Edit/Write tools are refused (e.g. a session-worktree guard) instead of `sed -i`.

Usage:
    DRY=1 python patch-anchored.py spec.json   # check every anchor, write nothing
    python patch-anchored.py spec.json         # apply, only if every anchor passes

spec.json is a JSON list of edits:
    [{"file": "<path>", "old": "<exact anchor text>", "new": "<replacement>"}, ...]

- `file` is used exactly as given (absolute, or relative to the current directory).
- Each `old` must occur EXACTLY once in its file, evaluated in spec order (an earlier edit's
  result is what a later edit on the same file sees); a mismatch reports the actual count.
- Files are read and written as bytes, so each file keeps its CRLF or LF line endings. When a
  target file uses CRLF, LF-only `old`/`new` text in the spec is normalized to CRLF first.
- Nothing is written unless every edit in the spec validates.
"""
import json
import os
import sys


def to_file_eol(text, crlf):
    data = text.encode("utf-8")
    if crlf:
        data = data.replace(b"\r\n", b"\n").replace(b"\n", b"\r\n")
    return data


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    dry = os.environ.get("DRY", "").strip().lower() in ("1", "true", "yes")
    with open(argv[1], "r", encoding="utf-8") as fh:
        spec = json.load(fh)
    if not isinstance(spec, list) or not spec:
        print("spec must be a non-empty JSON list of {file, old, new}", file=sys.stderr)
        return 2

    contents = {}  # path -> bytes (pending result)
    originals = {}
    failures = []
    for i, edit in enumerate(spec):
        path, old, new = edit.get("file"), edit.get("old"), edit.get("new")
        if not isinstance(path, str) or not isinstance(old, str) or not isinstance(new, str) or not old:
            failures.append(f"edit {i}: needs string file, non-empty old, string new")
            continue
        if path not in contents:
            try:
                with open(path, "rb") as fh:
                    contents[path] = originals[path] = fh.read()
            except OSError as exc:
                failures.append(f"edit {i}: {path}: cannot read ({exc})")
                continue
        data = contents[path]
        crlf = b"\r\n" in data
        old_b, new_b = to_file_eol(old, crlf), to_file_eol(new, crlf)
        count = data.count(old_b)
        if count != 1:
            failures.append(f"edit {i}: {path}: anchor found {count} times (need exactly 1)")
            continue
        contents[path] = data.replace(old_b, new_b, 1)
        print(f"edit {i}: {path}: anchor OK")

    if failures:
        for line in failures:
            print("FAIL " + line, file=sys.stderr)
        print(f"{len(failures)} edit(s) failed; no file written", file=sys.stderr)
        return 1
    if dry:
        print(f"DRY=1: all {len(spec)} edit(s) validate; no file written")
        return 0
    for path, data in contents.items():
        if data != originals[path]:
            with open(path, "wb") as fh:
                fh.write(data)
            print(f"wrote {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
