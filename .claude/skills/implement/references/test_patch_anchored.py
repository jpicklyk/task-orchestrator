"""Tests for patch-anchored.py (proposal 610b9a9f, adoption condition (d)).

Run: python3 -m unittest discover -s .claude/skills/implement/references -p "test_*.py"
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HELPER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "patch-anchored.py")


class PatchAnchoredTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def write_bytes(self, name, data):
        path = os.path.join(self.dir, name)
        with open(path, "wb") as fh:
            fh.write(data)
        return path

    def read_bytes(self, path):
        with open(path, "rb") as fh:
            return fh.read()

    def run_spec(self, edits, dry=False):
        spec = self.write_bytes("spec.json", json.dumps(edits).encode("utf-8"))
        env = dict(os.environ)
        env.pop("DRY", None)
        if dry:
            env["DRY"] = "1"
        return subprocess.run(
            [sys.executable, HELPER, spec], env=env, capture_output=True, text=True
        )

    def test_crlf_file_patched_with_lf_spec_text_keeps_crlf(self):
        path = self.write_bytes("a.kt", b"fun f() {\r\n    a()\r\n    b()\r\n}\r\n")
        result = self.run_spec([{"file": path, "old": "    a()\n    b()\n", "new": "    c()\n"}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read_bytes(path), b"fun f() {\r\n    c()\r\n}\r\n")

    def test_lf_file_is_patched_and_stays_lf(self):
        path = self.write_bytes("a.mjs", b"const x = 1\nconst y = 2\n")
        result = self.run_spec([{"file": path, "old": "const y = 2", "new": "const y = 3"}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read_bytes(path), b"const x = 1\nconst y = 3\n")

    def test_backslashes_survive_byte_for_byte(self):
        original = b"s.replace(/\\r\\n/g, '\\n')\n"
        path = self.write_bytes("b.mjs", original)
        result = self.run_spec([{"file": path, "old": "/\\r\\n/g", "new": "/\\r?\\n/g"}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.read_bytes(path), b"s.replace(/\\r?\\n/g, '\\n')\n")

    def test_multi_file_spec_with_one_bad_anchor_writes_nothing(self):
        good = self.write_bytes("good.kt", b"val a = 1\r\n")
        bad = self.write_bytes("bad.kt", b"val b = 2\r\n")
        result = self.run_spec([
            {"file": good, "old": "val a = 1", "new": "val a = 9"},
            {"file": bad, "old": "val missing = 0", "new": "val b = 9"},
        ])
        self.assertEqual(result.returncode, 1)
        self.assertIn("anchor found 0 times", result.stderr)
        self.assertIn("no file written", result.stderr)
        self.assertEqual(self.read_bytes(good), b"val a = 1\r\n")
        self.assertEqual(self.read_bytes(bad), b"val b = 2\r\n")
        self.assertEqual(sorted(os.listdir(self.dir)), ["bad.kt", "good.kt", "spec.json"])

    def test_duplicate_anchor_fails_with_its_count(self):
        path = self.write_bytes("dup.kt", b"x()\nx()\n")
        result = self.run_spec([{"file": path, "old": "x()", "new": "y()"}])
        self.assertEqual(result.returncode, 1)
        self.assertIn("anchor found 2 times", result.stderr)
        self.assertEqual(self.read_bytes(path), b"x()\nx()\n")

    def test_dry_run_validates_but_writes_nothing(self):
        path = self.write_bytes("d.kt", b"val a = 1\n")
        result = self.run_spec([{"file": path, "old": "val a = 1", "new": "val a = 2"}], dry=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("DRY=1", result.stdout)
        self.assertEqual(self.read_bytes(path), b"val a = 1\n")

    def test_crlf_spec_text_against_lf_file_fails_closed(self):
        path = self.write_bytes("e.mjs", b"a\nb\n")
        result = self.run_spec([{"file": path, "old": "a\r\nb", "new": "c"}])
        self.assertEqual(result.returncode, 1)
        self.assertIn("anchor found 0 times", result.stderr)
        self.assertEqual(self.read_bytes(path), b"a\nb\n")


if __name__ == "__main__":
    unittest.main()
