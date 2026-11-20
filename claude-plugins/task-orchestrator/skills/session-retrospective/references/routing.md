# Classification, routing and trend scoping

The detail behind Steps 3f, 4, 5e, 6 and 7b of the skill: how each finding is classified, where a defect goes, how project trends stay under the project, and what a proposal carries. Call shapes: [`../../_generated/call-shapes.md`](../../_generated/call-shapes.md); read each write's `data.results[i]`.

Contents: [Finding rubric](#finding-rubric) | [F-line format](#f-line-format) | [Trend scoping](#trend-scoping) | [Trend writes](#trend-writes) | [Defect routing](#defect-routing) | [Agent-discipline slips](#agent-discipline-slips) | [Proposal content](#proposal-content)

## Finding rubric

Every finding from Step 3 gets a **kind** and a **scope** (Step 3f).

| Kind | Meaning | Route |
|---|---|---|
| `defect` | a tool, hook, script or skill broke its own contract | `bug` (Step 5e), plus friction evidence on a trend |
| `process` | the workflow worked as written but cost too much or fit badly | `trend` (Steps 4 and 6) |
| `agent-discipline` | an agent ignored an instruction it had | `memory` (below); no item, no trend |
| `positive` | a pattern worth keeping | `trend` tagged `positive` |

**Scope** is the layer that owns the wrong text or code:

| Scope | Owner |
|---|---|
| `product` | the server; plugin code, hooks, scripts and skills |
| `global-process` | floor config and bundled rules |
| `project` | per-root config, project rules, project skills |

A finding with no clear owner takes `global-process`. A finding with `route=none` is reported and nothing else.

## F-line format

`improvement-signals` carries one line per finding, ahead of the trend and proposal lines:

```
F<n> kind=<defect|process|agent-discipline|positive> scope=<product|global-process|project> dim=<dimension> route=<trend <key>|bug|memory|none>: <finding>
```

`<dimension>` is the trend dimension of Step 6. Step 7c files an issue only for a product-scope trend. A line that does not parse reads `scope=global-process`.

## Trend scoping

Project trends live apart from global ones so one project's pattern never counts toward another's.

- **Project tag.** `project-<root8>`: the first 8 hex characters of the project root id. A tag cannot hold a colon, so no other spelling is valid.
- **Project container.** A `Retrospective Trends` container that is a child of the project root: list `parentId` the root, `type: "container"`, match the exact title; else create it (`type: "container"`, `tags: ["container"]`, `parentId` the root).
- **Global list.** Every trend of the global container: `retrospective-trend` items minus those carrying a `project-*` tag.
- **Project list.** `type: "retrospective-trend"`, `tags: ["project-<root8>"]`.

```to-call
{"tool": "items_read", "select": "list", "args": {"type": "retrospective-trend", "tags": ["project-<root8>"], "role": "queue", "limit": 100, "fields": ["summary", "createdAt", "modifiedAt"]}}
```

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["retrospective-trend"], "role": "queue", "limit": 100, "fields": ["summary", "createdAt", "modifiedAt"]}}
```

A finding of scope `project` is matched only against the project list; every other finding only against the global list. Match by title and summary; when unsure, search with the finding's keywords (`tags: ["retrospective-trend"]`). A match increments `Sessions: N` in Step 6; anything else is a new-trend candidate. With no project root (a personal root or none) a project finding is recorded in `improvement-signals` and no trend is written.

## Trend writes

One `notes_write` for this run's evidence notes, one `items_write` for creates and summary updates.

- **Recurrence:** upsert `evidence-<YYYY-MM-DD>-<retro-short-id>` (role `work`: what happened, retro item id, cost or impact) and update the trend `summary` (`Sessions: N+1`, `Last seen: <date>`, condensed).
- **New pattern:** create `trend: <kebab-key> - <one-line claim>` (`type: "retrospective-trend"`, `tags: ["retrospective-trend", "<dimension>"]`, `parentId` the trends container, `priority: "low"`, summary `<observation>. Sessions: 1. Last seen: <date>.`) with its first evidence note. A project trend adds the tag `project-<root8>` and sits under the project's `Retrospective Trends` container. `positive` is added for a positive pattern.
- **Standing trends** `fix-cycle-cost` and `retro-cost` get one evidence note every run ([`evaluation.md`](evaluation.md), "Standing trends").
- **Retire** (addressed, obsolete, superseded): `advance` `cancel` with `summary: "archived: <reason>"`. **Graduation:** put `GRADUATED -> proposal <short-id>` in the summary.

Only `create`, `update`, note upserts, `cancel` and `reopen` touch a trend, never `start` or `complete`. The summary is the single distilled truth, rewritten on every update. A `schema_violation` on the trend type means trend memory is unavailable: record it in `improvement-signals` and skip the trend writes and Step 7.

## Defect routing

Step 5e runs after 5d and before Step 6, with no session threshold: a defect is routed on its first sighting. Defects come from the metrics `defects[]` (each has a `fingerprint`) and from `defect`-kind findings without one.

**Dedup.** Two unscoped searches, each over open items (drop terminal hits): the fingerprint when the finding has one, and the key nouns or `<tool> <code>` with `tags: ["bug"]`, because a hand-filed `[bug]` observation carries no `Fingerprint:` line. A hit on either is a match.

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "<fingerprint>", "kinds": ["item"], "matchMode": "substring", "limit": 10}}
```

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "<tool> <code>", "kinds": ["item"], "tags": ["bug"], "matchMode": "substring", "limit": 10}}
```

- **Defect from an input observation** (an `agent-observation` read in Step 1 already records it): create nothing; the action is `existing <short>`.
- **Open match:** upsert `evidence-<YYYY-MM-DD>-<retro8>` (role `work`) on it.
- **Only terminal matches, or no match:** create a new item. Its description is `Fingerprint: <fp>` on line 1 (omitted when the finding has none), for a regression `Regression of <short>` on the next line, then what the retrospective saw (items, run, count). The three tags are always set: `retro-reported` is what keeps a later retrospective from reading the item as a new input. The `observation-detail` note holds the tool, op, code and message, the items that hit it, the run, what a fix must change and, for a regression, the earlier item.
- **Scope `product` or `global-process`:** one `create_tree`, an `agent-observation` with no parent:

```to-call
{"tool": "items_write", "op": "create_tree", "args": {"root": {"title": "[bug] <tool> <op>: <code> - <message, at most 80 chars>", "type": "agent-observation", "priority": "low", "tags": ["agent-observation", "bug", "retro-reported"], "description": "Fingerprint: <fp>\n[Regression of <short>\n]<what the retrospective saw: items, run, count>"}, "notes": [{"itemRef": "root", "key": "observation-detail", "role": "queue", "body": "<tool, op, code, message; the items that hit it; the run; what a fix must change; [the earlier item]>"}]}}
```

- **Bug report upstream:** a `product` defect that meets the qualification in [`../../_shared/github-feedback.md`](../../_shared/github-feedback.md#bug-reports-product-defects) always joins the input of Step 5e's one `bug-report` run (always run; the command decides, and the gate is its to read); its `github-issue:` URL is recorded back on the observation, so a later retrospective finds it by the fingerprint.
- **Scope `project`:** check whether the root declares `bug-fix`:

```to-call
{"tool": "items_read", "select": "schema", "args": {"type": "bug-fix", "rootId": "<rootId>"}}
```

  `data.type` of `default` means the type is not declared. Declared: `items_write` `create` a `bug-fix` under the project root, `tags: ["bug", "retro-reported", "project-<root8>"]`, the same description; its queue notes are left to its planner. Not declared: the `create_tree` above with the extra tag `project-<root8>`.

Every routed defect also adds an evidence note on the matching `friction` trend (Step 6). A trend whose findings this run are all defects gets no proposal.

## Agent-discipline slips

No item and no trend is created. `actions-taken` lists the slip, and the report ends with one line per slip for the session that dispatched the retrospective to keep:

```
memory: <pattern> -> <correct behaviour> (<reason>)
```

The dispatching session saves each line to its memory when the retrospective returns.

## Proposal content

A proposal carries a concrete change: schema, trait, rule or run-profile edits with exact YAML or rule text; skill text only for mechanics or generic engineering practice; hooks naming event, matcher and purpose. Its scope is its trend's: a `project-*` tag means project scope (`Scope: project-specific` in the `proposal` note). Any other trend is `product` only when every F-line routed to the trend this run is `scope=product`, else `global-process` (`Scope: product` or `Scope: global-process`). A project-scope proposal is created under a per-project `Improvement Proposals` container beneath the project root (found or created as the `Retrospective Trends` container above).
