# Search and Discovery

This document explains the FTS5 full-text search and graph-aware discovery features available on
`query_items`, `query_notes`, and `query_dependencies`. For reference-level API documentation, see
[`api-reference.md`](./api-reference.md). For ready-to-use workflow recipes, see
[`workflow-guide.md`](./workflow-guide.md#full-text-search-fts5-recipes).

---

## Architecture

### Two-Table FTS5 Index

Work-item titles and summaries are indexed in two complementary SQLite FTS5 virtual tables:

| Table | Tokenizer | Best for |
|---|---|---|
| `work_items_fts_trigram` | `trigram` | Substring search, case-insensitive, partial word matches |
| `work_items_fts_text` | `porter unicode61` | Natural language, stemming (e.g., "running" matches "run") |

Note bodies are indexed analogously in `notes_fts_trigram` and `notes_fts_text`.

FTS5 tables are maintained by SQLite triggers installed by the V7 database migration. All writes
to `work_items` and `notes` keep the FTS indexes up to date automatically.


### matchMode

The `matchMode` parameter controls which table(s) are queried:

| matchMode | Tables queried | When to use |
|---|---|---|
| `"auto"` (default) | Both — trigram + text, fused via RRF | Best coverage; recommended for most searches |
| `"substring"` | Trigram only | Substring match via trigram; requires ≥3-char token |
| `"text"` | Text (porter+unicode61) only | Natural language / stemming queries |

### Reciprocal Rank Fusion (RRF)

When `matchMode="auto"`, the server queries both FTS5 tables independently and merges the ranked
results using **Reciprocal Rank Fusion** (RRF, Cormack & Clarke 2009):

```
score(doc) = Σ_sources  1 / (k + rank_in_source(doc))
```

where `k = 60` (the standard RRF constant). Documents that appear in both tables receive
contributions from both lists; documents in only one table receive a lower combined score.

**Effect:** A term like "running" that matches both the trigram index (substring "running") and the
text index (stemmed "run") will score higher than one that only matches one table. Items relevant to
both exact-match and semantic-match queries surface first.

### Input Handling

Both MCP tools and both REST search routes run through one search service. The query string is
split on ASCII whitespace into plain terms, matched with AND semantics; a term carries no query
syntax. The SQLite adapter is the only place FTS5 syntax is produced: it wraps each term in double
quotes (doubling any embedded `"`), so `*`, `:`, `-`, parentheses and the operator words `AND`,
`OR`, `NOT`, `NEAR` are matched literally.

An empty or whitespace-only query is rejected. For `matchMode="substring"`, a query whose every
term is shorter than 3 characters is rejected too (the trigram index minimum).

You do not need to escape or quote search terms — pass them as plain text.

---

## Scope Filtering

All FTS5 search operations accept a `scope` object to narrow the result set structurally. Every
filter is applied inside the FTS5 query, before ranking and before the 100-hit cap.

### scope.ancestorId

Restricts matches to items in a subtree. The server builds a recursive CTE:

```sql
WITH RECURSIVE subtree(id) AS (
  SELECT id FROM work_items WHERE id = ?
  UNION ALL
  SELECT wi.id FROM work_items wi JOIN subtree s ON wi.parent_id = s.id
)
-- then: WHERE wi.id IN subtree
```

This walks all descendants at any depth from the given ancestor item. Use `scope.ancestorId` to
scope a search to a feature, container, or project subtree.

### scope.itemId

Restricts matches to a single item only (exact UUID match). Useful for re-searching content on
a specific item.

### scope.tags (query_items only)

OR-matches: only items that have at least one of the listed tags are included (case-insensitive).

### scope.role (query_items only)

Exact role filter on the work item (`queue`, `work`, `review`, `terminal`, `blocked`).

**Note for note search:** `query_notes.search` supports only `scope.itemId` and
`scope.ancestorId`. A `scope.role` or `scope.tags` value is rejected with `VALIDATION_ERROR`
(an explicit `null` counts as absent). To list notes filtered by phase, use
`query_notes(operation="list", role="queue")` instead.

### REST principal scope

On `GET /api/v1/search` and `GET /api/v1/notes/search`, a token's `root_ids` and `tags_include`
are applied the same way: inside the query, before ranking and the 50-hit page, so a restricted
token gets a full page of in-scope hits. `tags_include` is exact and case-sensitive against the
item's own tags (for a note hit, its owning item's tags); `root_ids` admits an item whose ancestor
chain, itself included, contains a listed id, at any depth.

---

## Backlinks

`query_dependencies(operation="backlinks", itemId=...)` returns reverse-direction dependency edges:
all items that hold a dependency edge pointing AT the given item (`dependencies.to_item_id = itemId`).

This is different from `direction="incoming"` on the `get` operation. The `get` operation shows
dependency edges FROM or TO the queried item for traversal. The `backlinks` operation answers:
**"who references this item?"** — useful for impact analysis, tracing dependents, and understanding
blast radius before changing an item.

The query uses the existing database index on `to_item_id` — no full table scan.

---

## Response Shape

All FTS5 search operations return the same shape:

```json
{
  "hits": [
    {
      "kind": "item",
      "itemId": "uuid",
      "title": "Owning item title",
      "field": "title",
      "snippet": "…~32 tokens with <mark>matched term</mark>…",
      "score": 0.0325,
      "matchedIn": ["trigram", "text"]
    }
  ],
  "totalHits": 5,
  "nextOffset": 20,
  "truncated": false
}
```

`field` names the field that actually contains a match (`title` or `summary`; `title` when both
do), and `snippet` is taken from that field. `title` is the item's own title for an item hit and the
owning item's title for a note hit (omitted if the item could not be read).

For note search, hits additionally include `noteKey` and `field` is always `"body"`.

### limit

FTS mode accepts `limit` from 1 to 100 (default 20); a value above 100 is rejected with
`VALIDATION_ERROR` instead of being capped. An `offset` at or beyond `totalHits` still returns an
empty page.

### Score Interpretation

`score` is the descending RRF fused value — higher means more relevant. Typical values:

| Score range | Interpretation |
|---|---|
| > 0.030 | Strong match in both tables (top-tier, appears in both trigram and text results) |
| 0.015–0.030 | Good match in one table only |
| < 0.015 | Weak match (low rank in a single table) |

These ranges are illustrative. The absolute values depend on the size of the result set and the
distribution of ranks across both tables.

### totalHits

Every page is a slice of one deterministic, totally ordered list: a fixed-size candidate window is
fetched from each FTS5 table regardless of `offset`, fused by RRF into a single order (score
descending, then hit kind, then ascending id), then capped at 100 entries before the `offset`/`limit` slice is
taken. `totalHits` is the size of that capped, fused list — it is identical on every page of the
same query (it is **not** the raw database match count) and is at most 100. When `truncated=true`,
more than 100 matches existed before the cap; refine the query or add scope filters. `truncated` can
now be `true` on page 1 — it is a property of the query, not of any individual page.

### nextOffset

`null` when the requested `offset` is at or beyond `totalHits` (no more results). Pass the returned
value as `offset` in the next call to paginate; because pagination is offset-independent, pages
never overlap or skip entries for a stable DB state.

---

## explain=true

Setting `explain=true` adds an `explain` object to each hit:

```json
{
  "explain": {
    "trigramRank": -8.1,
    "textRank": -6.4,
    "rrfK": 60
  }
}
```

`trigramRank` and `textRank` are raw BM25 scores from FTS5 (lower absolute value = higher relevance
in FTS5's ranking). `rrfK` is always 60.

Use `explain=true` only when debugging ranking — it adds one JSON object per hit and is off by
default.

---

## Requirements

- **SQLite ≥ 3.45** — required for the FTS5 trigram tokenizer used by the substring table.
  The server bundles SQLite via the `xerial/sqlite-jdbc` driver (included in the Docker image),
  so no local SQLite installation is required.
- Integration tests for FTS5 run against a real SQLite database (the `SqliteTestDatabase` fixture).
