"""Mirror current/docs/ into the GitHub wiki repo with path flattening and link rewriting.

GitHub wikis are flat (no subdirectories) and links must drop the .md extension.
This script handles both:
  - integration-guides/<name>.md  ->  integration-guides-<name>.md (flattened)
  - integration-guides/index.md   ->  Integration-Guides.md       (overview page)
  - Markdown links rewritten:
      ](Home.md)                              -> ](Home)
      ](./api-reference.md#anchor)            -> ](api-reference#anchor)
      ](integration-guides/bare-mcp.md)       -> ](integration-guides-bare-mcp)
      ](integration-guides/index.md)          -> ](Integration-Guides)
      ](../api-reference.md)  (in guides)     -> ](api-reference)
  - External URLs (https://...md) are left untouched via negative lookahead.

Usage:
  python sync_wiki.py <source-docs-dir> <wiki-repo-dir>
  python sync_wiki.py <source-docs-dir> <wiki-repo-dir> --dry-run

Exit code 0 on success. Output lists every file written; absence of output means no-op.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

GUIDES_DIR = "integration-guides"
INDEX_PAGE = "Integration-Guides"


def discover_names(src_dir: Path) -> tuple[list[str], list[str]]:
    """Return (top_level_stems, guide_stems) discovered from the source directory.

    top_level_stems excludes _Sidebar (underscore-prefix files are not link targets).
    guide_stems excludes 'index' (which maps to a separate overview page name).
    """
    top_level = sorted(
        p.stem for p in src_dir.glob("*.md") if not p.stem.startswith("_")
    )
    guides_dir = src_dir / GUIDES_DIR
    guide_stems: list[str] = []
    if guides_dir.is_dir():
        guide_stems = sorted(p.stem for p in guides_dir.glob("*.md") if p.stem != "index")
    return top_level, guide_stems


def rewrite_links_top_level(text: str, top_level: list[str], guides: list[str]) -> str:
    """Rewrite Markdown links inside top-level wiki pages (Home, _Sidebar, etc.).

    External URLs (matched by https?:) are left untouched via negative lookahead.
    """
    for name in top_level:
        text = re.sub(
            rf"\]\((?!https?:)(?:\./)?{re.escape(name)}\.md",
            f"]({name}",
            text,
        )
    text = re.sub(
        rf"\]\((?!https?:)(?:\./)?{re.escape(GUIDES_DIR)}/index\.md",
        f"]({INDEX_PAGE}",
        text,
    )
    for g in guides:
        text = re.sub(
            rf"\]\((?!https?:)(?:\./)?{re.escape(GUIDES_DIR)}/{re.escape(g)}\.md",
            f"]({GUIDES_DIR}-{g}",
            text,
        )
    return text


def rewrite_links_guide(text: str, top_level: list[str], guides: list[str]) -> str:
    """Rewrite Markdown links inside files originating from integration-guides/.

    Guide files reference parents via ../<name>.md and siblings via <name>.md.
    """
    for name in top_level:
        text = re.sub(
            rf"\]\((?!https?:)\.\./{re.escape(name)}\.md",
            f"]({name}",
            text,
        )
    text = re.sub(
        r"\]\((?!https?:)index\.md",
        f"]({INDEX_PAGE}",
        text,
    )
    for g in guides:
        text = re.sub(
            rf"\]\((?!https?:){re.escape(g)}\.md",
            f"]({GUIDES_DIR}-{g}",
            text,
        )
    return text


def transform(src_dir: Path, dst_dir: Path, dry_run: bool = False) -> list[Path]:
    """Read every .md from src_dir, transform, write to dst_dir. Return paths written."""
    top_level, guides = discover_names(src_dir)
    written: list[Path] = []

    for f in sorted(src_dir.glob("*.md")):
        original = f.read_text(encoding="utf-8")
        rewritten = rewrite_links_top_level(original, top_level, guides)
        out = dst_dir / f.name
        if _write_if_changed(out, rewritten, dry_run):
            written.append(out)

    guides_src = src_dir / GUIDES_DIR
    if guides_src.is_dir():
        for f in sorted(guides_src.glob("*.md")):
            original = f.read_text(encoding="utf-8")
            rewritten = rewrite_links_guide(original, top_level, guides)
            out_name = f"{INDEX_PAGE}.md" if f.stem == "index" else f"{GUIDES_DIR}-{f.stem}.md"
            out = dst_dir / out_name
            if _write_if_changed(out, rewritten, dry_run):
                written.append(out)

    return written


def _write_if_changed(out: Path, content: str, dry_run: bool) -> bool:
    """Write content to out if it differs from existing. Return True if changed."""
    if out.exists() and out.read_text(encoding="utf-8") == content:
        return False
    if dry_run:
        print(f"would write {out.name}")
    else:
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(content, encoding="utf-8")
        print(f"wrote {out.name}")
    return True


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("src", type=Path, help="Source docs directory (e.g., current/docs)")
    p.add_argument("dst", type=Path, help="Wiki repo directory (e.g., wiki-repo)")
    p.add_argument("--dry-run", action="store_true", help="Print actions without writing")
    args = p.parse_args(argv)

    if not args.src.is_dir():
        print(f"error: source directory not found: {args.src}", file=sys.stderr)
        return 2
    if not args.dst.is_dir():
        print(f"error: destination directory not found: {args.dst}", file=sys.stderr)
        return 2

    written = transform(args.src, args.dst, dry_run=args.dry_run)
    if not written:
        print("no changes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
