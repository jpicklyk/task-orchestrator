---
name: run-wave
description: "Front door for running a multi-item wave of MCP work items through a generated run plan — resolves a frontier of ready/resumed items, derives per-item seat stages from their resolved schemas, and executes the plan via the Workflow tool (Method A) or direct Agent dispatch (Method B) when Workflow is unavailable. Use when a user says: run the wave, launch the wave, run a wave over <feature>, run these ready items as a wave, run-wave, resume run r-…, or when a task notification arrives from task-orchestrator:implement-wave. NOT for a single Direct-tier fix (use /status-progression or a direct dispatch) and NOT for headless unattended queue draining (use /ralph). Does not itself decide whether to plan or implement — it executes a run plan over items whose specification/task-scope notes already exist."
argument-hint: "[ancestorId | itemIds] [--method A|B] [--mode shared|per-item] [--worktree <path> --branch <name>] [--resume <runId>] [--claim]"
---

# run-wave — Multi-Item Run Plan Front Door

This skill resolves a frontier of ready (and resumable) MCP work items into a **run plan** —
a deterministic set of per-item seat stages derived from each item's resolved schema — and
executes that plan either through the `Workflow` tool (**Method A**) or through direct `Agent`
dispatch (**Method B**, the fallback when `Workflow` is unavailable or the server doesn't yet
serve enough capability data for Method A). Both methods execute the **same** run plan and the
same seat prompts — Method B reuses the Method A core byte-for-byte (see
`references/method-b.md`).

The skill itself does no planning arithmetic. All frontier resolution, edge classification, and
stage derivation happens in a pure Node helper (`scripts/run-planner.mjs` /
`run-planner-lib.mjs`). This skill's job is: call the helper with the right inputs, show the
human the plan, launch it, and drive post-run verification and advancement. Side effects (MCP
calls, `git worktree add`, `Workflow`/`Agent` dispatch, `advance_item`) stay in this prose, never
in the helper.

**Do not attempt to replicate the helper's algorithm in your head.** If a helper call fails or
its output looks wrong, report the raw stderr/stdout rather than improvising a plan by reading
MCP responses directly — the whole point of the helper split is that frontier/edge/stage logic
is fixture-tested, not reasoned about fresh each run.

---

## Helper resolution

Every helper call in this skill (and in the two references) is of the form:

```
node "<helper>" <cmd> --in <scratchpad>/run-wave/<runId>/<file>
```

Resolve `<helper>` once, at the start of Step 0, and reuse the same resolved path for every
later call in this run (record it as `state.helper`):

1. Try `${CLAUDE_PLUGIN_ROOT}/scripts/run-planner.mjs`. `${CLAUDE_PLUGIN_ROOT}` substitutes
   inside plugin skill bodies (the same mechanism the `code-modernization` plugin's commands
   rely on) — confirm the literal string `${CLAUDE_PLUGIN_ROOT}` did **not** survive into the
   command you are about to run, and that `probe` (see F0 below) exits 0.
2. If the literal survived, or `probe` exits non-zero, fall back to the dev-checkout path
   `<git toplevel>/claude-plugins/task-orchestrator/scripts/run-planner.mjs`, when that path
   exists. Set `state.helperFallback: true` and add `helper-fallback` to `meta.degradations` —
   under this fallback, `probe`'s `phase0Hooks` reflects the checkout, not the installed plugin
   cache, so Step 0 F4 will read `entryMode: pre-entered` even when the installed cache actually
   has Phase 0 hooks. Report this degradation rather than silently trusting the pre-entered path.
3. If neither resolves, **stop** and tell the user to refresh the plugin cache (see
   `claude-plugins/CLAUDE.md` → "Plugin Discovery and Cache Refresh") — do not attempt a manual
   plan.

---

## Step 0 — Capability check and fallbacks

Run every check below before touching MCP state. Each row's fallback is a rule for THIS run —
re-probe every invocation (capabilities can change between sessions).

| # | Check | How | Fallback |
|---|---|---|---|
| F0 | Helper resolvable | `node "<helper>" probe` exits 0 | dev-checkout path (see Helper resolution above); else stop with the cache-refresh pointer |
| F1 | `Workflow` tool callable | `Workflow` already in your tool list, or `ToolSearch("select:Workflow")` resolves it | Method B (pass `--method B` to `plan` in Step 3 so the plan document's `meta.method` is `"B"`) |
| F2 | Server `features` cover `seats`, `dispatchBySeat`, `rules` | the first `query_items(operation="schema")` response's `features` field, **not** `probe` — `probe` reports plugin/hook capability (version, hooks, allow rules), never the server's schema `features` | missing `features`/`seats` → Method B with implicit per-phase owners (no interim seat map — the planner derives `planner`/`implementer` ownership itself when a schema is seat-less); missing `rules` → Method B, resolve note `skillPointer`s as Claude skills instead of served rules |
| F3 | Protocol rules served | `rulesServed` from `query_rules(operation="list")` — the snapshot's own `rulesServed` field — includes `protocol.entry-seat`, `protocol.in-phase-seat`, `protocol.read-only-agent`; **not** `probe`, which carries no `rulesServed` field at all | **stop** Method A with: "rules not synced; config-sync pushes `.taskorchestrator/rules/*.md` at session start (plugin ≥ 3.8.0)"; Method B may still proceed, falling back to skill prose for the same guidance |
| F4 | Phase 0 hooks present | `probe.phase0Hooks` | `entryMode: 'pre-entered'` (pass `--entry pre-entered` to `plan` in Step 3, rather than only noting it in prose) — the front door advances each item queue→work itself before launch, instead of the entry seat calling `advance_item(start)` |
| F5 | Size guideline | `probe.workflowSizeGuideline` compared against the plan's `meta.estAgents` (medium: warn above ~10 agents; large: warn above ~50) | advisory line only — never blocks the run |
| F6 | Allow rules present | `probe.allow` (`{workflow, mcp, bashGit, bashNode}`) | warn: "this run will pause at the first un-allowed seat tool call in manual/accept-edits mode"; list the specific missing allow entries; **never** edit `.claude/settings.json` yourself |
| F7 | Open run already exists | Check the target's (parent or single item's) `session-tracking` note for a `run-wave: <runId> state=<phase> slug=run/<runId>` pointer line, or `manage_plan_documents(operation="list", rootId)` filtered to slugs matching `run/*/state`, `updatedAt` within the last 7 days, `phase != "closed"` | an open run found → go straight to the **Resume matrix** below instead of Steps 1–3 |

Report every fallback you took (F0/F1/F2/F3/F6) in the run's eventual `session-tracking` Friction
section — a silent fallback is a degradation the user should be able to see later.

---

## Invocation arguments

Each `argument-hint` flag maps to a `plan` CLI flag one-for-one, except the two rows that never
reach `plan` at all:

| Argument | `plan` flag | Notes |
|---|---|---|
| `ancestorId \| itemIds` | — | Scopes Step 2's snapshot calls; never passed to `plan` itself |
| `--method A\|B` | `--method A\|B` | Forces the method instead of the server-capability default |
| `--mode shared\|per-item` | `--mode shared\|per-item` | |
| `--worktree <path> --branch <name>` | `--worktree <path> --branch <name>` | `shared` mode only; both or neither, else `plan` exits 3. Reuses an already-created feature worktree/branch (e.g. `/implement` Step 2's) instead of `assembleArgs` deriving a fresh one |
| `--resume <runId>` | — | Routes straight to the **Resume matrix** below instead of Steps 1–3 |
| `--claim` | — | Consumed in Step 1 (`claim_item` before entering work) instead of `advance_item(start)` |

Step 0 F4's fallback also maps to a `plan` flag, not just a prose note: when `probe.phase0Hooks`
is false, pass `--entry pre-entered` to `plan` in Step 3. Likewise, when F1 (or F2/F3) selects Method B,
pass `--method B` to `plan` so `meta.method` is `"B"` — the Method B prompt addendum and the blind
test-author default key on it.

---

## Steps 1–9

| Step | Action |
|---|---|
| 1 Scope | Resolve the target: `ancestorId` (a parent feature/container UUID) or explicit item ids from `$ARGUMENTS`. Resolve `rootId` from `.taskorchestrator/config.yaml` → `project.rootId`. If `--claim` was passed, this run uses the claim-mode alternative instead of `advance_item(start)`: claim each item (`claim_item`) and enter work with the claimant's actor and a `ttlSeconds` covering the expected run duration; no in-run edges are computed for claimed items (claims settle their own ordering) |
| 2 Snapshot | Make the snapshot calls (`get_next_item` ready + `role:"work"` resume candidates, `get_blocked_items`, `query_items(operation:"overview")`, `query_items(operation:"schema")` per candidate, `query_rules(operation:"list")`, `get_context` per distinct parent, `query_notes` for work-role candidates, `git rev-parse --show-toplevel` + `git fetch origin` + `git rev-parse origin/main` + `git worktree list --porcelain`). Assemble `run-wave/snapshot-v1` (field reference: `references/snapshot.md`; projected fields only — no note bodies, no guidance text) and write it to `<scratchpad>/run-wave/snapshot.json` — **no `runId` exists yet**, so this path carries no `<runId>/` segment |
| 3 Plan | `node "<helper>" plan --in <scratchpad>/run-wave/snapshot.json --scratchpad <scratchpad>` (add `--method B` whenever F1/F2/F3 selected Method B, so `meta.method` is `"B"`) — **`--scratchpad` is mandatory here**, not optional: the project profile's `verify`/`searchScope` entries may carry a literal `<scratchpad>` placeholder that only this flag substitutes, and `plan` refuses (exit 3) rather than silently shipping the un-substituted literal. Once `plan` returns a `runId`, copy the snapshot and the returned plan document to `<scratchpad>/run-wave/<runId>/snapshot.json` and `<scratchpad>/run-wave/<runId>/plan.json` — **every step from here on reads from that `<runId>/` pair**, never from the pre-`runId` snapshot path. Then `node "<helper>" explain --in <scratchpad>/run-wave/<runId>/plan.json`. **Checkpoint 1:** show the `explain` table to the user. In collaborative mode, wait for explicit approval before continuing. In autonomous mode, proceed unless `meta.excluded` or `meta.degradations` is non-empty — either one requires a human look first. If the run is empty (no items admitted), stop and report every deferral/exclusion with its reason — do not silently no-op |
| 4 Worktrees | `git worktree add` for each entry in `meta.worktreesToCreate`. On resume, reuse an existing branch/worktree rather than recreating it (mirrors `/implement` Step 2, `WORKTREE.md`) |
| 5 Persist | `manage_plan_documents(operation="stash", rootId, slug="run/<runId>", bodyFromFile=<scratchpad>/run-wave/<runId>/plan.json)` when the server can resolve that path (relative to `AGENT_CONFIG_DIR`); when it can't (e.g. `AGENT_CONFIG_DIR` is a container mount the scratchpad isn't under), fall back to `body=<the plan-doc-v1 object>` inline and note the fallback. Write `run/<runId>/state` the same way (`bodyFromFile` first, inline fallback second) with `phase: "planned"`; append the pointer line `run-wave: <runId> state=planned slug=run/<runId>` to the parent's (else the single item's) `session-tracking` note, actor `orchestrator:<session>` |
| 6a Launch A | `Workflow({name: "task-orchestrator:implement-wave", args: <the plan's args object, passed as a real object — never a JSON-encoded string>})`. Record the returned `taskId` **and** the Workflow runtime's own run identifiers — `state.wfRunId` and `state.wfScriptPath` — from that call's result; these are distinct from the TO `runId` (`r-…`) and are what a later resume needs to reattach to this exact Workflow run. Set `phase: "launched"`. **End the turn here** — the result arrives later as an async task notification; say so explicitly to the user |
| 6b Launch B | Run the Method B loop — see `references/method-b.md` |
| 7 Post-run | Runs only when triggered by the Method A notification, or by Method B's own loop reaching `next → {complete: true}`. See `references/post-run.md` |
| 8 Review | Method B generic review (F-9 in the design) unless the project's `run-profile.json` sets `review: "handoff"`, in which case control passes to the project's own review step (this repo: `/implement` Step 5) |
| 9 Loop | Re-run from Step 2. Items surfaced in the last `advance_item` response's `unblockedItems` join the next frontier automatically |

Steps 1–5 are the **planning** half of a run; Steps 6–9 are the **execution** half. A run can
span many orchestrator turns — see turn-boundary discipline below.

---

## Turn-boundary protocol

This skill's loop is a protocol across turns, not a single-shot script:

- **Write `state.phase` before every turn ends.** Whatever step you are mid-way through, the
  state document on disk (`run/<runId>/state`) must reflect where you actually are, not where
  you expect to be next. A turn that ends without persisting state is a turn that resume (F7)
  cannot recover.
- **Increment `turns`** once per orchestrator turn in which you act for this run — this feeds
  the `orchestrator-turns` field of the provenance line (`references/post-run.md`).
- **Re-entry always goes through Step 0 F7, never from conversation context.** After
  compaction, a session restart, or simply picking the thread back up later, do not assume the
  run state you remember is still current — re-read the pointer/state document and resume from
  what it says. This is why the skill's own trigger phrases include "task notification from
  task-orchestrator:implement-wave" and "resume run r-…": both should land you back in Step 0,
  not mid-script.
- **A result whose `runId`/`planDocSlug` matches no open state is reported, never acted on.**
  If a notification or Method B envelope references a run this session has no record of (already
  closed, or from a different session), surface it to the user as informational and stop —
  do not retroactively advance items based on it.

---

## Resume matrix

When Step 0 F7 finds an open run, dispatch on `state.phase`:

| `state.phase` | Resume action |
|---|---|
| `planned` | Re-validate with `node "<helper>" validate --in <scratchpad>/run-wave/<runId>/plan.json`, then relaunch (Method A: re-issue `Workflow`; Method B: start the `next` loop) |
| `launched` (Method A) | Notification not yet seen — check `/workflows` or the task's status first. If the session was itself restarted and the run is gone but `state.wfRunId`/`state.wfScriptPath` were recorded (Step 6a), stop the stale task (`TaskStop`) and relaunch with `Workflow({scriptPath: state.wfScriptPath, resumeFromRunId: state.wfRunId})`; if those keys are missing (an older run, or the launch step never got to record them), re-plan from Step 2 instead (seats are rerun-safe — re-dispatching an already-entered seat is not destructive) |
| `launched` (Method B) | Resume `next` from the saved state document; any in-flight stage with no recorded `stage-result` is re-dispatched (rerun-safe) |
| `notified` / `post-run` | Continue `references/post-run.md` at the first step not yet recorded in state |
| `review` | Continue at Step 8 above |

A `closed` state means the run is finished — Step 0 F7 should not surface it as an open run
(the `phase != "closed"` filter on `manage_plan_documents(list)` already excludes it; a stale
pointer line in `session-tracking` pointing at a closed run is stale prose, not a live run).

---

## See also

- `references/snapshot.md` — the `run-wave/snapshot-v1` field reference Step 2 fills.
- `references/method-b.md` — the direct-Agent-dispatch execution loop used when `Workflow` is
  unavailable or Step 0 selects Method B, including scheduling, envelope handling, the
  declarations scan, and the degradations Method B declares relative to Method A.
- `references/post-run.md` — the post-run protocol shared by both methods: result verification,
  the actor audit, note-filling, the batched `advance_item` call, review hand-off, and the human
  checkpoints that close out a run.
- `/status-progression` — for a single item's own advance/gate state outside a run plan.
- `/ralph` — for headless, unattended queue draining instead of an interactive run plan.
- `/implement` — the project-specific workflow this skill is invoked from for Parallel-tier and
  multi-item Delegated-tier waves.
