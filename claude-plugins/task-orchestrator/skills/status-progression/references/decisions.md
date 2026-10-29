# Decisions: raise, answer, resume

The decision loop has five steps: **raise** (a seat or coordinator asks), **declare** (the class
exists in config), **see** (the director board shows it), **answer** (a director or human rules),
**resume** (the orchestrator or coordinator unblocks the item and re-dispatches). This page is the
orchestrator and coordinator side (the orchestration connection). The director side is in
`/task-orchestrator:director`.

## 1. Who does what

| Actor | Does |
|---|---|
| A seat (implementer, reviewer, ...) | Raises under its own seat token, with `block: true`. Never resumes. |
| Orchestrator or coordinator | Raises for a token-less agent that returned `awaiting_decision`; later resumes the item and re-dispatches. |
| Director or human | Answers or withdraws. The director has no `advance`, so it cannot resume. |

## 2. Raise

Raise with `block: true` so the item blocks as `awaiting_decision` in the same unit. A bare
`block` has no `decisionId` and nothing for anyone to answer.

```to-call
{"tool": "runs_write", "op": "raise", "args": {"class": "deviation", "block": true, "dedupeKey": "c04baf7c:implementer:dev-1", "question": "The planned approach fails because X. Proceed with Y?", "options": [{"key": "proceed", "label": "Use Y", "consequence": "Y replaces the planned approach"}, {"key": "keep", "label": "Keep the plan", "consequence": "The plan stands and X is worked around"}], "recommended": "proceed", "itemId": "c04baf7c"}}
```

- `dedupeKey` convention: `<short-id>:<raiser>:<kind>-<n>`. Re-raising the same key returns the
  existing decision, and when that decision is already answered nothing is blocked
  (`blocked: false`). A new deviation on the same item therefore needs a new `-<n>`.
- Token: a seat passes its seat token. A coordinator passes the coordinator token for a member of
  an open or paused run, and no token for an item outside a run.
- Errors on `data.results[0].error`: `invalid_request` with `/class` (the class is not declared;
  the detail names the valid ones), `seat_forbidden`, `not_claim_holder`, `invalid_transition`
  (the item cannot block from its role), `payload_too_large` (4096 bytes), `idempotency_mismatch`.

## 3. Declared classes

The built-in classes are `deviation` and `question`; `run-budget` and `attempt-cap` are raised by
the server only. A custom class must be declared under `decision_classes:` in config, or the raise
fails `invalid_request /class`. See `/task-orchestrator:manage-schemas` (Decision classes). A class
with `answerers: human-only` cannot be answered over stdio: the answer needs a credential of kind
`human`.

## 4. Resume only when answered

Never resume while the decision is open or acknowledged: the item would run on without the ruling.
Resume when `items_read` `blocked` shows `resumable: true` for the entry, or when `context`
`decisions[]` shows the item's `decisionId` as answered, or no longer lists it (withdrawn decisions are
left out):

```to-call
{"tool": "items_read", "select": "blocked", "args": {"ancestorId": "98d452e9"}}
```

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "c04baf7c", "trigger": "resume"}}
```

Pass the coordinator token for a member of an open or paused run, and no token otherwise. A
`resumable: false` entry with reason `awaiting_decision` means: ask the director to answer it.
Reasons `manual` and `resource` are not decisions; see `/dependency-manager`.

## 5. Carry the ruling

The agent that raised is gone; the ruling must reach the next one. After the resume, upsert an
orchestrator-owned `fix-decisions-<n>` note on the item (role = the `newRole` the resume answered).
Put in it the decision id, the chosen option key (from `context` `decisions[]`), and the label, text
and rationale when a director connection can read `runs_read` `decision`. A withdrawn decision means
"proceed per the queue notes". Then re-dispatch the phase owner. `agents/implementer.md` already
treats `fix-decisions-*` as binding.

## 6. Coordinators

`/task-orchestrator:run-wave` owns resume and re-dispatch: Step 2 (before the snapshot) and post-run
Step 6. `/task-orchestrator:ralph` does not raise or resume yet: resume a ralph-blocked item by hand
with the rule in section 4.
