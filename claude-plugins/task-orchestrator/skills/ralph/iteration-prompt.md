# Ralph Iteration — Drain One TO Item

This prompt is the per-iteration workflow. Operating principles, output discipline, and what NOT to do all live in the **Ralph Iteration** output style — read them there, not here. This document covers only the steps for *this specific* iteration.

## Per-iteration variables

- **Filter expression:** `${filter}` — keys: `tag`, `type`, `priority`, `parentId`. May be empty (matches any claimable queue item).
- **Actor identity:** `${actor_id}` (kind: `${actor_kind}`)
- **Claim TTL:** `${ttl}` seconds

---

## Step 1 — Atomically find and claim a candidate

Use `claim_item` selector mode — a single atomic MCP call that finds and claims in one operation, eliminating the race window of a two-call query-then-claim pattern.

Translate the filter expression into selector fields:

| Filter key | Selector field |
|---|---|
| `tag=X` | `tags: "X"` (any-match, comma-separated for multiple) |
| `type=X` | `type: "X"` |
| `priority=X` | `priority: "X"` |
| `parentId=X` | `parentId: "X"` (full UUID or 4+ char hex prefix) |

```
claim_item(
  claims=[{
    selector: {
      role: "queue",
      orderBy: "oldest",
      <...filter fields from expression...>
    },
    ttlSeconds: ${ttl},
    claimRef: "${actor_id}"
  }],
  requestId: "<fresh-uuid>",
  actor: { id: "${actor_id}", kind: "${actor_kind}" }
)
```

`orderBy: "oldest"` drains the queue in FIFO order (oldest items first), ensuring fair processing across all queue items.

> **Note:** The `claims` array must contain exactly one entry. `claims.size > 1` is rejected with error code `multi_claim_not_supported` regardless of mode.

| Result | Action |
|---|---|
| `success` with `selectorResolved: true` | Proceed to Step 2 with the resolved `itemId` |
| `no_match` (kind=permanent) | No items match the filter — emit `RALPH_OUTCOME: {"status": "no-item"}` and exit |
| `already_claimed` | TOCTOU race (rare) — emit `RALPH_OUTCOME: {"status": "skip", "reason": "TOCTOU race on selector resolve"}` and exit |
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
- **Bug-fix items carry the `needs-test-author` notes** — fill `test-plan` before advancing to work, write the regression test red-first, and record verdict `independent-degraded (temporal-only)` in the `test-independence-audit` note.

If `/schema-workflow` cannot complete because a required note can't be filled (you don't have the information, or filling it would require input the iteration can't get), emit:

```
RALPH_OUTCOME: {"status": "gate-blocked", "itemId": "<uuid>", "reason": "<which note key, why it can't be filled>"}
```

**If `advance_item` rejects a transition into WORK with `errorCode: "resource_unavailable"`** (a
shared resource the item declares — via a `resources:` trait — is currently held by another item;
`errorKind` is `"transient"`, distinct from a gate block), this is **not** a gate-blocked or error
condition — it is expected contention, and the claimed item did not do anything wrong. Do NOT retry
`advance_item` on the same item; the lock will not free up within this iteration's lifetime, and
spin-retrying just burns budget against a lock this iteration cannot control. Instead:

1. Release the claim on this item: `claim_item(releases=[{"itemId": "<uuid>"}], actor={...})`.
2. Emit the skip outcome, naming the contended resource key(s) from the failure's
   `contendedResources` field:

```
RALPH_OUTCOME: {"status": "skip", "itemId": "<uuid>", "reason": "resource_unavailable: <contendedResources>"}
```

The loop driver treats `skip` as a neutral outcome — no circuit-breaker penalty — and the next
iteration will claim a *different* item (this one stays unclaimed and claimable again once you
release it). If every candidate the loop encounters is contended on the same key, that is a signal
for the human operator to inspect `GET /api/v1/resources/leases` or `get_context(itemId=...)`, not
something this iteration should try to resolve by waiting.

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
