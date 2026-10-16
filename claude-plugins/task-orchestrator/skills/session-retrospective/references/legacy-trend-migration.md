# Legacy Trend Migration

One-time migration of the legacy `memory/retrospectives.md` trend file into trend items under the `Retrospective Trends` container. Called from `session-retrospective` Step 4.1; any `memory/retrospectives.md` content, in whatever legacy shape, migrates to MCP rather than to a history file.

Call shapes are in [`../../_generated/call-shapes.md`](../../_generated/call-shapes.md). A write is `{ops: [...]}` of flat elements (one per `to-call` block here). Results come back as `data.results[i]` in request order with `data.summary {ok, failed}`; read ops of an ops tool (`config` `get`, `validate`, `effective`) answer there too. These calls are non-atomic: if any element fails, even in a one-element call, the answer is `ok: false` with top-level `error.code: partial_failure` (no detail, no fix) beside the full `data`, and the cause is that element's `data.results[i].error` (`code`, `message`, `detail`, `fix`). Any other top-level `error.code` is a call-level rejection (envelope, unknown field, access).

**Condition** (checked at Step 4.1): the Trends container is absent or empty AND `memory/retrospectives.md` exists with at least one `- <kebab-key>: ...` entry line. This is self-terminating: after migration the file is a pointer stub with no entry lines, so the condition cannot match again for the project.

**Procedure.** Create and verify BEFORE touching the file, so an interrupted migration leaves content duplicated (recoverable), never lost.

1. **Create the Trends container** (if 4.1 did not find it empty). Use the declared container type; a `schema_violation` element error lists `data.results[0].error.detail.availableTypes`.

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "Retrospective Trends", "summary": "Cross-session trend memory for retrospectives.", "type": "container", "tags": ["container"], "priority": "low"}}
```

2. **Read `retrospectives.md` in full** (the last whole-file read this skill makes). Parse every `- <kebab-key>: ...` entry under each `## ` section, mapping the section header to a dimension tag:

   | Section header | Dimension tag |
   |---|---|
   | Schema Effectiveness | `schema-effectiveness` |
   | Delegation Patterns | `delegation` |
   | Note Quality | `note-quality` |
   | Friction | `friction` |
   | Extension Candidates | `extension-candidate` |

   Ignore `## Meta` narrative sections and the Improvement Proposals pointer section (already MCP-owned). An entry still marked `[ARCHIVED]` migrates as a trend item and is then retired with `advance` `transition`, `trigger: "cancel"`, `summary: "archived: migrated from legacy file, was already archived"`.

3. **Create one trend item per entry**, batched (about 10 per `items_write` call): title from the kebab key plus a distilled claim; summary = the condensed observation with the existing `Sessions: N` and `Last seen` carried over verbatim, plus any `GRADUATED -> proposal <id>` pointer already present.

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "trend: <kebab-key> - <one-line claim>", "summary": "<condensed observation>. Sessions: <N>. Last seen: <YYYY-MM-DD>.", "type": "retrospective-trend", "tags": ["retrospective-trend", "<dimension>"], "parentId": "<trends-container-uuid>", "priority": "low"}}
```

   If a create's `data.results[i].error.code` is `schema_violation` for `retrospective-trend`, the server floor does not declare the type yet: stop, report "trend memory unavailable", and leave the file untouched.

   Then upsert one `evidence-migrated` note per item (role `work`), batched, holding the ORIGINAL entry text verbatim, as the provenance record:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<new-trend-uuid>", "key": "evidence-migrated", "role": "work", "body": "<original entry text, verbatim>"}}
```

4. **Verify:** parsed entry count must equal created item count. Read the count from `page.total`.

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["retrospective-trend"], "parentId": "<trends-container-uuid>", "limit": 1}}
```

   **On a mismatch, stop and report; do NOT stub the file.** The condition still matches, so the next run retries.

5. **Only then rewrite `retrospectives.md`** as a pointer stub holding the Trends container UUID, the active-trends query (the 4.2 listing block of the skill), a note that history stays frozen in `retrospectives-history.md` (never migrated; a provenance archive), and the migration date plus this retrospective's item id.

6. **Record it** in the current retrospective's `actions-taken` note (Step 8b): entry count migrated and the container UUID.

**Multi-project note.** `retrospectives.md` was per-project memory (one file per Claude Code project directory); the Trends container is per database. Several projects on one MCP server converge on one shared Trends container once each has migrated. This is intended, the same process-global model as `Session Retrospectives` and `Improvement Proposals`.
