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
| `"substring"` | Trigram only | Case-insensitive substring; requires ≥3-char token |
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

### Input Sanitization

User-supplied query strings pass through `FtsQuerySanitizer` before reaching FTS5:

1. Split on whitespace into tokens.
2. Escape double-quote characters inside each token (`"` → `\"`).
3. Wrap each token in double-quotes — making it an FTS5 phrase term.
4. Join with spaces (implicit AND in FTS5's default mode).
5. FTS5 operator words (`AND`, `OR`, `NOT`, `NEAR`) are neutralized by wrapping — they become
   literal search terms rather than boolean operators.

For `matchMode="substring"`, an additional guard rejects inputs where every token is shorter than
3 characters (the trigram index minimum).

You do not need to escape or quote search terms — pass them as plain text.

---

## Scope Filtering

All FTS5 search operations accept a `scope` object to narrow the result set structurally before
or alongside the FTS5 match.

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

OR-matches: only items that have at least one of the listed tags are included.

### scope.role (query_items only)

Exact role filter on the work item (`queue`, `work`, `review`, `terminal`, `blocked`).

**Note for note search:** `query_notes.search` does not support `scope.role`. To list notes
filtered by phase, use `query_notes(operation="list", role="queue")` instead.

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

For note search, hits additionally include `noteKey` and `field` is always `"body"`.

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

`totalHits` is the size of the in-memory RRF-fused list for the current call, not the true database
total. The repository fetches up to `limit + offset + 1` rows per FTS table, so for large result
sets the true match count may exceed `totalHits`. When `truncated=true`, refine the query or add
scope filters.

### nextOffset

`null` when there are no more results. Pass this value as `offset` in the next call to paginate.

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
- **H2 (test environment):** FTS5 is SQLite-only. When running in an H2 database (unit test
  environment), all `search` operations return empty results. Integration tests for FTS5 use a
  real SQLite database.
