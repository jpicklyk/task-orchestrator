"""Tests for sync_wiki transform logic."""
from __future__ import annotations

from pathlib import Path

import pytest

from sync_wiki import (
    discover_names,
    rewrite_links_guide,
    rewrite_links_top_level,
    transform,
)


@pytest.fixture
def fake_docs(tmp_path: Path) -> Path:
    """A minimal source-docs tree mirroring the real layout."""
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "Home.md").write_text(
        "[Quick Start](quick-start.md)\n"
        "[API](./api-reference.md#claim_item)\n"
        "[Overview](integration-guides/index.md)\n"
        "[Bare](integration-guides/bare-mcp.md)\n"
        "[External](https://github.com/x/y/blob/main/CHANGELOG.md)\n",
        encoding="utf-8",
    )
    (docs / "_Sidebar.md").write_text(
        "- [Home](Home.md)\n- [Bare MCP](integration-guides/bare-mcp.md)\n",
        encoding="utf-8",
    )
    (docs / "api-reference.md").write_text("# API\n", encoding="utf-8")
    (docs / "quick-start.md").write_text("# Quick Start\n", encoding="utf-8")
    guides = docs / "integration-guides"
    guides.mkdir()
    (guides / "index.md").write_text(
        "[Bare](bare-mcp.md)\n[QS](../quick-start.md)\n", encoding="utf-8"
    )
    (guides / "bare-mcp.md").write_text(
        "[QS](../quick-start.md)\n[API](../api-reference.md#claim_item)\n[Sibling](claude-md-driven.md)\n",
        encoding="utf-8",
    )
    (guides / "claude-md-driven.md").write_text("body\n", encoding="utf-8")
    return docs


def test_discover_names(fake_docs: Path) -> None:
    top_level, guides = discover_names(fake_docs)
    assert top_level == ["Home", "api-reference", "quick-start"]
    assert "_Sidebar" not in top_level
    assert guides == ["bare-mcp", "claude-md-driven"]
    assert "index" not in guides


def test_rewrite_top_level_drops_md_extension() -> None:
    out = rewrite_links_top_level(
        "[X](Home.md)", top_level=["Home"], guides=[]
    )
    assert out == "[X](Home)"


def test_rewrite_top_level_preserves_anchor() -> None:
    out = rewrite_links_top_level(
        "[X](api-reference.md#claim_item)",
        top_level=["api-reference"],
        guides=[],
    )
    assert out == "[X](api-reference#claim_item)"


def test_rewrite_top_level_handles_dot_slash_prefix() -> None:
    out = rewrite_links_top_level(
        "[X](./api-reference.md)", top_level=["api-reference"], guides=[]
    )
    assert out == "[X](api-reference)"


def test_rewrite_top_level_flattens_guides_index() -> None:
    out = rewrite_links_top_level(
        "[O](integration-guides/index.md)", top_level=[], guides=[]
    )
    assert out == "[O](Integration-Guides)"


def test_rewrite_top_level_flattens_guide_files() -> None:
    out = rewrite_links_top_level(
        "[B](integration-guides/bare-mcp.md)",
        top_level=[],
        guides=["bare-mcp"],
    )
    assert out == "[B](integration-guides-bare-mcp)"


def test_rewrite_top_level_leaves_external_urls_untouched() -> None:
    out = rewrite_links_top_level(
        "[C](https://github.com/x/y/blob/main/CHANGELOG.md)",
        top_level=["CHANGELOG"],
        guides=[],
    )
    assert "https://github.com/x/y/blob/main/CHANGELOG.md" in out


def test_rewrite_guide_handles_parent_refs() -> None:
    out = rewrite_links_guide(
        "[QS](../quick-start.md)",
        top_level=["quick-start"],
        guides=[],
    )
    assert out == "[QS](quick-start)"


def test_rewrite_guide_handles_sibling_refs() -> None:
    out = rewrite_links_guide(
        "[Sib](claude-md-driven.md)",
        top_level=[],
        guides=["claude-md-driven"],
    )
    assert out == "[Sib](integration-guides-claude-md-driven)"


def test_rewrite_guide_handles_index_ref() -> None:
    out = rewrite_links_guide("[Up](index.md)", top_level=[], guides=[])
    assert out == "[Up](Integration-Guides)"


def test_rewrite_guide_preserves_anchor_on_parent_ref() -> None:
    out = rewrite_links_guide(
        "[API](../api-reference.md#claim_item)",
        top_level=["api-reference"],
        guides=[],
    )
    assert out == "[API](api-reference#claim_item)"


def test_transform_writes_expected_files(fake_docs: Path, tmp_path: Path) -> None:
    dst = tmp_path / "wiki"
    dst.mkdir()
    written = transform(fake_docs, dst)
    written_names = sorted(p.name for p in written)
    assert "Home.md" in written_names
    assert "_Sidebar.md" in written_names
    assert "Integration-Guides.md" in written_names
    assert "integration-guides-bare-mcp.md" in written_names
    assert "integration-guides-claude-md-driven.md" in written_names


def test_transform_is_idempotent(fake_docs: Path, tmp_path: Path) -> None:
    dst = tmp_path / "wiki"
    dst.mkdir()
    first = transform(fake_docs, dst)
    second = transform(fake_docs, dst)
    assert first, "first run should write something"
    assert second == [], "second run on unchanged source must be a no-op"


def test_transform_rewrites_home_links_correctly(
    fake_docs: Path, tmp_path: Path
) -> None:
    dst = tmp_path / "wiki"
    dst.mkdir()
    transform(fake_docs, dst)
    home = (dst / "Home.md").read_text(encoding="utf-8")
    assert "(quick-start)" in home
    assert "(api-reference#claim_item)" in home
    assert "(Integration-Guides)" in home
    assert "(integration-guides-bare-mcp)" in home
    assert "https://github.com/x/y/blob/main/CHANGELOG.md" in home
    assert ".md)" not in home.replace("CHANGELOG.md)", "")


def test_transform_rewrites_guide_links_correctly(
    fake_docs: Path, tmp_path: Path
) -> None:
    dst = tmp_path / "wiki"
    dst.mkdir()
    transform(fake_docs, dst)
    bare = (dst / "integration-guides-bare-mcp.md").read_text(encoding="utf-8")
    assert "(quick-start)" in bare
    assert "(api-reference#claim_item)" in bare
    assert "(integration-guides-claude-md-driven)" in bare
    assert ".md)" not in bare


def test_transform_handles_missing_guides_dir(tmp_path: Path) -> None:
    src = tmp_path / "docs"
    src.mkdir()
    (src / "Home.md").write_text("[X](api-reference.md)\n", encoding="utf-8")
    (src / "api-reference.md").write_text("body\n", encoding="utf-8")
    dst = tmp_path / "wiki"
    dst.mkdir()
    written = transform(src, dst)
    assert sorted(p.name for p in written) == ["Home.md", "api-reference.md"]


def test_transform_dry_run_writes_nothing(fake_docs: Path, tmp_path: Path) -> None:
    dst = tmp_path / "wiki"
    dst.mkdir()
    transform(fake_docs, dst, dry_run=True)
    assert list(dst.iterdir()) == []
