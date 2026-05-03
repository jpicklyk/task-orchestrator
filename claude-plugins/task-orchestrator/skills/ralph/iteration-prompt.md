# Ralph Iteration — Drain One TO Item

This prompt is the per-iteration workflow. Operating principles, output discipline, and what NOT to do all live in the **Ralph Iteration** output style — read them there, not here. This document covers only the steps for *this specific* iteration.

## Per-iteration variables

- **Filter expression:** `${filter}` — keys: `tag`, `type`, `priority`, `parentId`. May be empty (matches any claimable queue item).
- **Actor identity:** `${actor_id}` (kind: `${actor_kind}`)
- **Claim TTL:** `${ttl}` seconds

---

## Step 1 — Find and claim a candidate

Query for unclaimed queue items matching the filter:

```
query_items(operation="search", role="queue", claimStatus="unclaimed", limit=10, ...)
```

Filter key → search arg mapping:

| Filter key | Mapped argument |
|---|---|
| `tag=X` | `tags="X"` (substring match) |
| `type=X` | `type="X"` |
| `priority=X` | `priority="X"` |
| `parentId=X` | `parentId="X"` (full UUID or 4+ char hex prefix) |

If the filter is empty, omit those arguments.

**If zero candidates:** emit `RALPH_OUTCOME: {"status": "no-item"}` and exit.

Order candidates by priority (high → medium → low). For each candidate in order, attempt:

```
claim_item(
  claims=[{ itemId: "<uuid>", ttlSeconds: ${ttl} }],
  requestId: "<fresh-uuid>",
  actor: { id: "${actor_id}", kind: "${actor_kind}" }
)
```

| Result | Action |
|---|---|
| `success` | Proceed to Step 2 with the claimed UUID |
| `already_claimed` | Drop and try the next candidate |
| All candidates already claimed | Emit `RALPH_OUTCOME: {"status": "skip", "reason": "all candidates already claimed"}` and exit |
| Other error | Emit `RALPH_OUTCOME: {"status": "error", "reason": "claim failed: <message>"}` and exit |

---

## Step 2 — Drive through the schema

Invoke `/schema-workflow` with the claimed item ID:

```
/schema-workflow <claimed-uuid>
```

The skill reads the item's schema at runtime and drives note-fill + phase advancement until the item reaches terminal role. Your job inside that flow:

- **Author note content** per each note's `guidance` field. The guidance is authoritative — follow it.
- **Do the actual work** the notes describe. Could be code changes, research, configuration edits, batch updates, anything. The schema decides what; you execute.
- **Run any verification** the spec note calls for (tests, linters, etc.).

If `/schema-workflow` cannot complete because a required note can't be filled (you don't have the information, or filling it would require input the iteration can't get), emit:

```
RALPH_OUTCOME: {"status": "gate-blocked", "itemId": "<uuid>", "reason": "<which note key, why it can't be filled>"}
```

If a tool fails, build breaks unexpectedly, or any other condition prevents progress:

```
RALPH_OUTCOME: {"status": "error", "itemId": "<uuid>", "reason": "<message>"}
```

---

## Step 3 — Commit and emit success outcome

If the work involved file changes, commit them with a message that references the item:

```
git -C <worktree-path> add <changed-files>
git -C <worktree-path> commit -m "<descriptive>: <title> (item <short-uuid>)"
```

If the schema's terminal phase declared push/PR steps, follow them. Otherwise, leaving the worktree with a commit is sufficient.

Emit the success marker as your final message:

```
RALPH_OUTCOME: {"status": "terminal", "itemId": "<full-uuid>", "summary": "<short description of what was done>"}
```
