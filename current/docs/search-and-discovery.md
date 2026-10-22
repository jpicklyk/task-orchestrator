# Search and Discovery

This document explains the FTS5 full-text search available on `items_read` (`select: search`) over item
content, note bodies and documents, the graph-aware discovery on `deps_read`, and the `related` select of
`items_read`, which fuses dependencies, links, tags and text into one ranked list. For reference-level API
documentation, see
[`api-reference.md`](./api-reference.md). For ready-to-use workflow recipes, see
[`workflow-guide.md`](./workflow-guide.md#full-text-search-fts5-recipes).

---

## Architecture

### Two-Table FTS5 Index

Work-item titles, summaries and descriptions are indexed in two complementary SQLite FTS5 virtual tables (no prefix index; every query term is quoted, so none is ever issued):

| Table | Tokenizer | Best for |
|---|---|---|
| `work_items_fts_trigram` | `trigram` | Substring search, case-insensitive, partial word matches |
| `work_items_fts_text` | `porter unicode61` | Natural language, stemming (e.g., "running" matches "run") |

Note bodies are indexed analogously in `notes_fts_trigram` and `notes_fts_text`, and document titles and
bodies in `documents_fts_trigram` and `documents_fts_text` (plan, rule and page documents alike).

FTS5 tables are maintained by SQLite triggers. All writes to `work_items`, `notes` and `documents`
keep the FTS indexes up to date automatically, so an edited document is searchable at once.

### Kinds

A search covers one or more kinds of content, fused into one ranking, one 100-hit cap and one paging
order:

| `kinds` value | Content | Hit address |
|---|---|---|
| `item` | work-item title, summary, description | `itemId` |
| `note` | note bodies | `itemId` and `noteKey` |
| `document` | document title and body (not archived) | `rootId`, `docKind` and `slug` |

With `kinds` absent the search covers every kind the other filters apply to. `role`, `tags` and `traits`
describe work items, so they apply to `item` and `note` only: with none of them set the default is all
three kinds, with any of them it is `item` and `note`. Naming `document` in `kinds` together with `role`,
`tags` or `traits` is `invalid_request` (pointer `/role`, `/tags` or `/traits`, the first one set in that
order); a filter is never silently dropped. A document is owned by its root item: `itemId` or `ancestorId`
set to that root finds the root's documents, any other id finds none.


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

Every transport runs search through one search service. The query string is
split on ASCII whitespace into plain terms, matched with AND semantics; a term carries no query
syntax. The SQLite adapter is the only place FTS5 syntax is produced: it wraps each term in double
quotes (doubling any embedded `"`), so `*`, `:`, `-`, parentheses and the operator words `AND`,
`OR`, `NOT`, `NEAR` are matched literally.

An empty or whitespace-only query is rejected, and so is a query containing a control character
(U+0000-U+001F or U+007F-U+009F other than tab, line feed, vertical tab, form feed, carriage return
and space): the call fails `invalid_request` on `/query`. For `matchMode="substring"`, a query whose every
term is shorter than 3 characters is rejected too (the trigram index minimum).

You do not need to escape or quote search terms — pass them as plain text.

---

## Scope Filtering

Every filter is applied inside the FTS5 query, before ranking and before the 100-hit cap.

### ancestorId

Restricts matches to items in a subtree. The server builds a recursive CTE:

```sql
WITH RECURSIVE subtree(id) AS (
  SELECT id FROM work_items WHERE id = ?
  UNION ALL
  SELECT wi.id FROM work_items wi JOIN subtree s ON wi.parent_id = s.id
)
-- then: WHERE wi.id IN subtree
```

This walks all descendants at any depth from the given ancestor item. Use `ancestorId` to scope a
search to a feature, container, or project subtree.

### itemId

Restricts matches to a single item only (exact id match). Useful for re-searching content on a
specific item.

### tags, role and traits

`tags` OR-matches (an item with at least one of the listed tags, case-insensitive), `role` is an exact
role filter (`queue`, `work`, `review`, `terminal`, `blocked`), and `traits` OR-matches trait names
(exact). All three filter on the item that owns the content (for a note, its item) and apply to `item`
and `note` kinds only; see [Kinds](#kinds).

### Principal scope

A token's `root_ids` and `tags_include` are applied the same way: inside the query, before ranking and
the 100-hit cap, so a restricted token gets a full page of in-scope hits. `tags_include` is exact and
case-sensitive against the item's own tags (for a note hit, its owning item's tags; for a document hit,
its root item's tags); `root_ids` is roots only: it admits an item whose stamped root id is a listed id (no
ancestor walk), so an id that is not a project root admits nothing. Only a request's own `ancestorId` traverses a subtree. A document is visible exactly when its root item is, so a token scoped to an id below
the root, or by a tag the root does not carry, does not see the root's documents.

---

## Backlinks

`deps_read` with `select: list` and `direction: incoming` returns reverse-direction dependency edges:
all items that hold a dependency edge pointing AT the given item (`dependencies.to_item_id = itemId`).

It answers **"who references this item?"** — useful for impact analysis, tracing dependents, and
understanding blast radius before changing an item.

The query uses the existing database index on `to_item_id` — no full table scan.

---

## Related

`items_read` with `select: related` answers **"what else is this item connected to?"** in one call: the
items and documents related to an item, ranked by four signals and fused with weighted RRF
(`weight / (60 + position)` per signal, summed):

| Signal | Weight | What it finds | Reasons |
|---|---|---|---|
| `edges` | 1.0 | Dependency counterparts, both directions (RELATES_TO first, then blocking) | `relates_to`, `blocks`, `blocked_by` |
| `links` | 1.0 | Resolved links from the item or its notes, and links to the item; more link rows rank higher | `links_to`, `linked_from` |
| `tags` | 0.5 | Items sharing a tag; a rarer tag (carried by fewer visible items) weighs more | `shared_tags:<n>` |
| `text` | 0.5 | Up to 8 distinctive words of the item and its notes, any-term stemmed match over items, notes and documents | `text` |

The text signal picks its words deterministically: split on anything that is not a letter or digit,
lowercase, keep words of 3 or more characters that are not all digits and not common words, then the
longest first (ties alphabetical). A note match counts as its owning item; the item itself never
appears. By default every signal stays inside the item's root; `acrossRoots: true` lifts that bound only.
The caller's scope applies before fusion and the cap of 50, as for search. `explain: true` adds each hit's
per-signal positions, the weights and `rrfK`. The full contract is in
[`api-reference.md`](./api-reference.md) (`items_read`, `related`).

```json
{ "select": "related", "itemId": "550e8400", "limit": 5 }
```

---

## Response Shape

`items_read` `search` answers a page of hits plus the paging fields:

```json
{
  "items": [
    {
      "kind": "item",
      "itemId": "550e8400",
      "title": "Owning item title",
      "field": "title",
      "snippet": "…~32 tokens with <mark>matched term</mark>…",
      "score": 0.0325,
      "matchedIn": ["trigram", "text"]
    },
    {
      "kind": "document",
      "rootId": "a1b2c3d4",
      "docKind": "plan",
      "slug": "auth-rollout",
      "title": "Auth rollout plan",
      "field": "body",
      "snippet": "…the <mark>matched term</mark> in the plan body…",
      "score": 0.0161,
      "matchedIn": ["text"]
    }
  ],
  "page": { "total": 5, "capped": false }
}
```

`field` names the field that actually contains a match (`title`, `summary` or `description` for an item,
the first of those in that order when several do; `title` or `body` for a document, `title` when both do;
always `body` for a note), and `snippet` is taken from that field. `title` is the item's own title for an
item hit, the owning item's title for a note hit (omitted if the item could not be read) and the
document's own title for a document hit (omitted when it has none). A note hit adds `noteKey`. A document
hit has `rootId`, `docKind` and `slug` instead of `itemId`; they address the document for `docs_read`.

### limit

`limit` is 1 to 100 (default 20); a value above 100 is rejected with `invalid_request` instead of
being capped. An offset at or beyond `page.total` returns an empty page.

### Score Interpretation

`score` is the descending RRF fused value — higher means more relevant. Typical values:

| Score range | Interpretation |
|---|---|
| > 0.030 | Strong match in both tables (top-tier, appears in both trigram and text results) |
| 0.015–0.030 | Good match in one table only |
| < 0.015 | Weak match (low rank in a single table) |

These ranges are illustrative. The absolute values depend on the size of the result set and the
distribution of ranks across both tables.

### page.total

Every page is a slice of one deterministic, totally ordered list: a fixed-size candidate window is
fetched from each FTS5 table regardless of `offset`, fused by RRF into a single order (score
descending, then hit kind, then ascending id), then capped at 100 entries before the `offset`/`limit` slice is
taken. `page.total` is the size of that capped, fused list — it is identical on every page of the
same query (it is **not** the raw database match count) and is at most 100. When `page.capped` is
true, more than 100 matches existed before the cap; refine the query or add scope filters. It is a
property of the query, not of any individual page.

### Paging

Pass the returned cursor as `cursor` in the next call; because pagination is offset-independent, pages
never overlap or skip entries for a stable DB state.

### Hit mix

The call log's `request_shape` for a search records `hitsByKind`: the number of hits of each searched
kind in the capped list (one key per searched kind, zeros kept, the same on every page). Each document hit
on the returned page also records its id and version as a target version, so a later read of the same
document can be joined to the search that surfaced it.

---

## explain

Setting `explain: true` adds an `explain` object to each hit:

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

Use `explain` only when debugging ranking — it adds one JSON object per hit and is off by
default.

---

## Requirements

- **SQLite ≥ 3.45** — required for the FTS5 trigram tokenizer used by the substring table.
  The server bundles SQLite via the `xerial/sqlite-jdbc` driver (included in the Docker image),
  so no local SQLite installation is required.
- Integration tests for FTS5 run against a real SQLite database (the `SqliteTestDatabase` fixture).
