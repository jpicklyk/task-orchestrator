---
name: run-wave
description: "Front door for running a multi-item wave of MCP work items through a generated run plan — resolves a frontier of ready/resumed items, derives per-item seat stages from their resolved schemas, opens a server run for them, and executes the plan via the Workflow tool (Method A) or direct Agent dispatch (Method B) when Workflow is unavailable. Use when a user says: run the wave, launch the wave, run a wave over <feature>, run these ready items as a wave, run-wave, resume run r-…, or when a task notification arrives from task-orchestrator:implement-wave. NOT for a single Direct-tier fix (use /status-progression or a direct dispatch) and NOT for headless unattended queue draining (use /ralph). Does not itself decide whether to plan or implement — it executes a run plan over items whose specification/task-scope notes already exist."
argument-hint: "[ancestorId | itemIds] [--method A|B] [--mode shared|per-item] [--worktree <path> --branch <name>] [--resume <runId>] [--claim] [--altitude-safe]"
---

# run-wave — Multi-Item Run Plan Front Door

This skill resolves a frontier of ready (and resumable) MCP work items into a **run plan** —
a deterministic set of per-item seat stages derived from each item's resolved schema — opens a
**server run** over those items, and executes the plan either through the `Workflow` tool
(**Method A**) or through direct `Agent` dispatch (**Method B**, the fallback when `Workflow` is
unavailable or the server doesn't yet serve enough capability data for Method A). Both methods
execute the **same** run plan and the same seat prompts — Method B reuses the Method A core
byte-for-byte (see `references/method-b.md`).

The skill itself does no planning arithmetic. All frontier resolution, edge classification, stage
derivation and the exact server ops are produced by a pure Node helper (`scripts/run-planner.mjs`
/ `run-planner-lib.mjs` / `run-exec-lib.mjs`). This skill's job is: call the helper with the right
inputs, show the human the plan, send the ops the helper renders, launch, and drive post-run
verification and advancement. Side effects (MCP calls, token files, `git worktree add`,
`Workflow`/`Agent` dispatch) stay in this prose, never in the helper. The Workflow runtime has no
MCP access: every `runs_write`, `runs_read` and coordinator `advance` call is yours.

**Do not attempt to replicate the helper's algorithm in your head.** If a helper call fails or
its output looks wrong, report the raw stderr/stdout rather than improvising a plan by reading
MCP responses directly — the whole point of the helper split is that frontier/edge/stage logic
is fixture-tested, not reasoned about fresh each run.

---

## The server run

A run-wave run is a server run (`current/docs/api-reference.md` → "Category: Runs"). Two ids name it:

- the **plan id** `runId` (`r-…`), from `plan`: the scratchpad directory, the plan document's
  `args.runId`, the provenance `run=` field and the run's `label`;
- the **server run id** (a UUID), from `runs_write` `open`: bound into the plan with `bind-run` as
  `args.serverRunId` and passed as `runId` to every `runs_write`/`runs_read` call.

The run view (`runs_read` `get`, `frontier`, `seats`) is the run's state: there is no plan or state
document on the server. The local scratch files (`<scratchpad>/run-wave/<runId>/plan.json`,
`state.json`, `result.json`) hold what the helper reads.

**Tokens.** `open` answers a `coordinatorToken` once (a keyed replay omits it); `assign` answers each
seat's `seatToken` once. Keep the coordinator token in this session only. Write each seat token, and
nothing else, as one line to the token file `run-ops assign` names
(`<scratchpad>/run-wave/<runId>/tokens/<short>.<seat>.token`); the seat prompt names that path and the
seat reads it. Never put a token in a prompt, a note, a log line, a commit, the plan or a helper
input, and never echo one back to the user. A lost coordinator token cannot be recovered: close the
run (a human credential may close it) and open another.

A seat envelope is stripped before it is persisted: any `seatToken` or `coordinatorToken` key is removed at any depth in both methods, and Method B's `stage-result` also redacts every value read from the run's token files. Method A strips by key only (the Workflow sandbox cannot read token files), so a token echoed under another key is not caught there. The coordinator token never has a token file, so its value is caught by key only in both methods.

**Which token goes on a call.** Every coordinator call (`update`, `assign`, `close`, the coordinator
`advance` calls, `withdraw` of a decision this run raised) carries the coordinator token as the
top-level `seatToken`; `end_seat` carries the seat's own token; `open` carries none.

**RESULTS.** A write tool answers `data.results[i]`, one entry per `ops[]` element: read each
element's own `ok` and `data.results[i].error` (`code`, `detail`, `fix`). A call-level
`partial_failure` only means "an element failed, look per element" and carries no detail. The
exception is an atomic batch (`atomic: true`): it answers only the failing element's own error with
`error.index`, and nothing was written.

**Retry classes the coordinator meets.**

| Answer | What to do |
|---|---|
| a success carrying `paused: {decisionId}` | the call landed and the run is now paused: stop assigning and launching, surface the decision to the user |
| `run_paused {pauseReason, pauseDecisionId}` | never retry. The run pauses on a `run-budget` or `attempt-cap` decision (or a manual pause); a director or a human answers it (`answer{option: "extend", amount}` adds `amount` in the breached dimension, whole tokens or US dollars; other options stop or skip). A coordinator token cannot answer. A withdrawn pause decision leaves the run paused until it closes |
| `run_at_capacity {active, max}` | transient: back off until a seat ends (`maxActiveSeats`), then re-send the same `assign`; no decision to answer |
| `unavailable` (kind shedding) | retry once after `retryAfterMs` |
| `unauthenticated` "The seat token is not accepted." | the token is unknown, ended, superseded or the run closed: a seat stops (`seat-token-rejected`); a lost coordinator token means close and re-open |
| `note_owned_by_other {ownerSeat}`, `seat_forbidden`, `not_claim_holder`, `decision_closed {decisionId, status}`, `forbidden` (`detail.required`), `not_found` (`detail.kind` `seat` or `decision`), `invalid_run_state`, `run_closed` | permanent: never retry unchanged; read `error.fix` |

**Decisions.** A writing seat that needs a ruling raises `runs_write` `raise {class: "deviation",
block: true, dedupeKey, question, options, recommended, itemId}` under its own token and stops with
`reason: "awaiting_decision"` and the `decisionId`. A raise with `block: true` blocks the item; an
answer never resumes it, and a blocked item allows only `resume` and `cancel`. The coordinator
withdraws (`runs_write` `withdraw {decisionId}`, coordinator token) only decisions it or its seats
raised that went stale — never a server-raised `run-budget` or `attempt-cap` decision. A stall is
never a reason to raise: see Stalled seats below. Pushing decisions to a director is not this skill's.

---

## Helper resolution

Every helper call in this skill (and in the references) is of the form:

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
   plan. Workflow scripts change only with a marketplace remove and re-add; skills and references
   need only `/reload-plugins`.

---

## Step 0 — Capability check and fallbacks

Run every check below before touching MCP state. Each row's fallback is a rule for THIS run —
re-probe every invocation (capabilities can change between sessions). F3 and F7 need a `rootId`
before Step 1 runs, so resolve it first by Step 1's rule (session context, then the `Config:` file,
then `.taskorchestrator/config.yaml`).

| # | Check | How | Fallback |
|---|---|---|---|
| F0 | Helper resolvable | `node "<helper>" probe` exits 0 | dev-checkout path (see Helper resolution above); else stop with the cache-refresh pointer |
| F1 | `Workflow` tool callable | `Workflow` already in your tool list, or `ToolSearch("select:Workflow")` resolves it | Method B (pass `--method B` to `plan` in Step 3 so the plan document's `meta.method` is `"B"`) |
| F2 | Server is 4.x with seats; `features` cover `seats`, `dispatchBySeat`, `rules` | `probe.serverCompat` first: non-null is the server-compat warning (a 3.x server, or one without the `seats` feature); `plan` also refuses (exit 3, `server incompatible: ...`) when the snapshot's `probe` carries it. Then the first `items_read(select:"schema")` response's `features` field — `probe` reports plugin/hook capability, never the server's schema `features` | `probe.serverCompat` non-null → **stop, both methods**, and show its line (upgrade the server or the plugin). Missing `features`/`seats` → stop as well (runs and seat tokens need them). Missing `rules` → **stop, both methods** (the server cannot serve the protocol rules, and `promptRules` in the shared prompt core marks the protocol key fatal for Method B too) |
| F3 | Protocol rules served | `config(ops:[{op:"rules", rootId}])` — its keys are the snapshot's `rulesServed` — includes `protocol.entry-seat`, `protocol.in-phase-seat`, `protocol.read-only-agent`; **not** `probe`, which carries no `rulesServed` field at all | **stop, both methods**, with: "protocol rules not served for this root". The reason: `promptRules` (`workflows/implement-wave.js`) marks the protocol key fatal, and Method B renders its seat prompts from that same core, so Method B cannot proceed either. Repair, then re-probe F3. (a) REST URL configured: start a new session; config-sync pushes the plugin's `bundled-rules/` at SessionStart to any initialized root that does not provide them. (b) No REST URL: run the `/task-orchestrator:init` rule seeding, or for each missing key `docs_write(ops:[{op:"upsert", rootId, kind:"rule", slug:"<key>", body:<${CLAUDE_PLUGIN_ROOT}/bundled-rules/<key>.md verbatim>}])`, then confirm the `config` `rules` get's `rulesVersion` is a hash listed for that key in `bundled-rules/manifest.json`; on a mismatch re-upsert once, then report |
| F4 | Phase 0 hooks present | `probe.phase0Hooks` (plugin 3.8.0 or later, the subagent-start `workflow-subagent` skip, and the phase-guard-record `seatToken` filter) | `entryMode: 'pre-entered'` (pass `--entry pre-entered` to `plan` in Step 3, rather than only noting it in prose) — the front door advances each item queue→work itself before launch, under the coordinator token, instead of the entry seat sending `advance` `transition` `start` |
| F5 | Size guideline | `probe.workflowSizeGuideline` compared against the plan's `meta.estAgents` (medium: warn above ~10 agents; large: warn above ~50) | advisory line only — never blocks the run |
| F6 | Allow rules present | `probe.allow` (`{workflow, mcp, bashGit, bashNode}`) | warn: "this run will pause at the first un-allowed seat tool call in manual/accept-edits mode"; list the specific missing allow entries; **never** edit `.claude/settings.json` yourself |
| F7 | Open run already exists | `runs_read(select:"list", rootId, status:"open")` (and `status:"paused"`), keeping the runs whose `hints.plan` is a plan id (`r-…`) of this skill; or the target's (parent or single item's) `session-tracking` pointer line `run-wave: <runId> run=<uuid8>` | an open or paused run found → `runs_read` `get`, `frontier` and `seats` for it, then go straight to the **Resume matrix** below instead of Steps 1–3 |

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
| `--claim` | — | Consumed in Step 1 (an `advance` `claim` before entering work) instead of a seat entry |
| `--altitude-safe` | `--altitude-safe` | Sets `args.altitudeSafe: true`: implement-wave returns the altitude-safe result (`rollup` counts, statuses, SHAs, file lists, note keys, reasons cut to 120 characters, no seat prose). Post-run Step 0 saves and verifies it like a full result |

Step 0 F4's fallback also maps to a `plan` flag, not just a prose note: when `probe.phase0Hooks`
is false, pass `--entry pre-entered` to `plan` in Step 3. Likewise, when F1 selects Method B,
pass `--method B` to `plan` so `meta.method` is `"B"` — the Method B prompt addendum and the blind
test-author default key on it.

**Director tier 2.** When a director session drives the run, it does not run these steps itself (its
guard denies the `node` helper, and the snapshot reads note bodies). It dispatches one named
background coordinator agent (`run_in_background: true`, `model` set) for Steps 0-5a with
`--altitude-safe`; the coordinator keeps the coordinator token and returns `{runId, serverRunId,
args}`. The director makes the one `Workflow` call with those `args`, and on its task notification
sends that coordinator a `SendMessage` to run post-run. Decisions land between runs. See
`skills/director/references/coordinator-tiers.md`.

---

## Steps 1–9

| Step | Action |
|---|---|
| 1 Scope | Resolve the target: `ancestorId` (a parent feature/container UUID) or explicit item ids from `$ARGUMENTS`. Resolve `rootId` in this order: (1) session context, the `Active project:` line or the `Personal root:` line the SessionStart hook injects; (2) else `project.rootId` from the file on that context's `Config:` line; (3) else `.taskorchestrator/config.yaml` → `project.rootId`. A personal root is anchor-only: it is the `rootId` for `config` `rules`, `docs_*` and `runs_write` `open`, never the snapshot's `ancestorId` (scope by the target). If no `rootId` resolves, stop and point at `/task-orchestrator:init` (or `--user`). If `--claim` was passed, this run uses the claim-mode alternative instead of a seat entry: claim each item (`advance(ops:[{op:"claim", itemId, ttlSeconds}])` with a `ttlSeconds` covering the expected run duration) before it enters work; no in-run edges are computed for claimed items (claims settle their own ordering) |
| 2 Snapshot | Make the snapshot calls (`items_read(select:"next")` ready + `role:"work"` resume candidates, plus every non-terminal item in the target scope that `items_read(select:"blocked")` reports as blocked only by other candidates (add these to `candidates` with `source:"ready"`; `next` does not return them, and without this the planner defers them as `not projected`; under `--entry pre-entered` those in-run edges defer the item cross-run), `items_read(select:"blocked")`, `items_read(select:"overview")`, `items_read(select:"schema")` per candidate, `config(ops:[{op:"rules", rootId}])`, `items_read(select:"context")` per distinct parent, `notes_read(select:"list", itemId, keys:[...])` for work-role candidates, and, for each pair of candidates with one parent that an unsatisfied `blocked` dependency edge joins, `notes_read(select:"get", itemId:<id>, key:<planner queue note key>)` per item, plus the summary of both items (from `items_read(select:"get", itemId:<id>)` or the overview) (projected into `scopes`: `files` is every repo path the note or summary names as a file to edit, including `Shared file, lock-serialized:` lines; `dependsOn` is the ids on `Depends on <id>:` lines), per candidate `notes_read(select:"list", itemId)` with no `keys` filter to find `carry-in-*` keys (when any exist, `dispositions` = the `carry-in-dispositions` note body via `notes_read(select:"get", itemId, key:"carry-in-dispositions")`, else the `## Carry-in dispositions` section of the planner queue note, else null; projected into `carryIns`), `git rev-parse --show-toplevel` + `git fetch origin` + `git rev-parse origin/main` + `git worktree list --porcelain`). Assemble `run-wave/snapshot-v1` (field reference: `references/snapshot.md`; projected fields only — no note bodies, no guidance text; the exceptions are `scopes` and `carryIns` (the dispositions body is the one place a note body enters), see `references/snapshot.md`) and write it to `<scratchpad>/run-wave/snapshot.json` — **no `runId` exists yet**, so this path carries no `<runId>/` segment |
| 3 Plan | `node "<helper>" plan --in <scratchpad>/run-wave/snapshot.json --scratchpad <scratchpad>` (add `--method B` whenever F1 selected Method B, so `meta.method` is `"B"`) — **`--scratchpad` is mandatory here**, not optional: the project profile's `verify`/`searchScope` entries may carry a literal `<scratchpad>` placeholder that only this flag substitutes, `plan` refuses (exit 3) rather than silently shipping the un-substituted literal, and seat token files live under the `project.scratchDir` it sets. Once `plan` returns a `runId`, copy the snapshot and the returned plan document to `<scratchpad>/run-wave/<runId>/snapshot.json` and `<scratchpad>/run-wave/<runId>/plan.json` — **every step from here on reads from that `<runId>/` pair**, never from the pre-`runId` snapshot path. Then, for each `meta.lateCarryIns` entry (a carry-in key on an item with no planner stage and no disposition line), write one disposition line (`<key>: in scope (AC n) | deferred to <item-id> | rejected because <reason>`) into that item's `orchestrator-carry-in-dispositions` note (notes_write upsert, the orchestrator's own note, never a planner-seat note) before launch; the implementer prompt names the key and requires it to be read. Then `node "<helper>" explain --in <scratchpad>/run-wave/<runId>/plan.json`. **Checkpoint 1:** show the `explain` table to the user, including its `Warnings:` line when present. Warnings are advisory and never block the run, unlike `meta.excluded`/`meta.degradations`; acting on a `shared-file edge` warning (deleting the edge, re-planning) is the user's call in collaborative mode, and the skill never deletes an edge by itself. In collaborative mode, wait for explicit approval before continuing. In autonomous mode, proceed unless `meta.excluded` or `meta.degradations` is non-empty — either one requires a human look first. When entered from post-plan-workflow's hand-off, plan approval counts as Checkpoint 1: proceed as in autonomous mode, and where a human look is required show the table and end the turn. A project dispatch contract is not part of the run plan: seat prompts are generated and never reference it. It serves the orchestrator (fallback hand-dispatches, the post-run commit map) and the reviewers; anything a seat must know goes in the item's `specification`/`task-scope` note, which seats read. If the run is empty (no items admitted), stop and report every deferral/exclusion with its reason — do not silently no-op (`run-ops open` exits 2 on an empty plan) |
| 4 Worktrees | `git worktree add` for each entry in `meta.worktreesToCreate`. On resume, reuse an existing branch/worktree rather than recreating it (mirrors `/implement` Step 2, `WORKTREE.md`) |
| 5 Open | `node "<helper>" run-ops --plan <scratchpad>/run-wave/<runId>/plan.json --phase open` renders `{tool: "runs_write", ops: [{op: "open", rootId, items, scopeId?, label: <runId>, title, hints: {plan, method, worktreeMode}, budget?}]}` (`budget` only when the profile sets its opt-in `runBudget`); send it as rendered, with no token. From the result keep `data.results[0].runId` (the server run UUID) and the `coordinatorToken` (this session only). A `duplicate` element means an item is already in another open run: stop and report it (F7 should have caught it). Then `node "<helper>" bind-run --in <scratchpad>/run-wave/<runId>/plan.json --server-run <uuid>` and overwrite `plan.json` with its output (exit 2 on anything but a full UUID). Run the **Parent check** below, then append the pointer line `run-wave: <runId> run=<first 8 hex of the uuid> parent=<skipped\|started\|blocked:<key,...>\|blocked:<errorCode>\|<role>>` to the parent's (else the single item's) `session-tracking` note with `notes_write` `upsert` (the whole body, the line appended). **Parent check:** the target parent is the item the pointer line goes on, and only when it is not itself a run item and not the run's `rootId` item; on every skip path (single-item run; the parent is the `rootId` item; the parent is itself a run item) write `parent=skipped` so the field is always present. `items_read(select:"context", itemId:<parent>)`: role `queue` and `gateStatus.canAdvance` → `advance(ops:[{op:"transition", itemId:<parent>, trigger:"start"}])` with the coordinator token and write `parent=started`; role `queue` and `canAdvance` false → tell the user the `gateStatus.missing` keys before launch, record them under Friction in `session-tracking`, write `parent=blocked:<key,...>` and continue (a gate block never stops the launch); an element error that is not a note-gate block (for example `dependency_unmet` or `resource_unavailable`) → write `parent=blocked:<errorCode>` and continue; any other role → no-op, write `parent=<role>`. Why: the server's start cascade moves only the immediate parent queue→work and not at all when that parent's queue notes are missing, so a target two levels up, resumed work-role items or a gate-blocked cascade would otherwise leave it in queue |
| 5a Assign | Under `entryMode: pre-entered`, first move every queue-role run item to work: one `advance` call with one `transition` `start` element per such item, under the coordinator token (read each element; an item that did not enter is excluded from the launch and reported). Then: **Method A** — `node "<helper>" run-ops --plan <plan> --phase assign` renders one `assign` element per schema-seat stage of every item (inserted stages such as `declarations-extractor` are not schema seats and run token-less, read-only) plus the aligned `tokenFiles` list; send it in ONE `runs_write` call under the coordinator token and write each successful `data.results[i].seatToken` to `tokenFiles[i]` (create the `tokens/` directory first). An element that failed (`run_at_capacity`: back off and re-send that element alone once a seat ends; `run_paused`: stop and surface) leaves its seat without a token file. Method A pre-assigns at launch, so `stalledSeats`/`maxActiveSeats` read every seat as assigned from the start (a declared degradation: they are advisory until each seat's first call). **Method B** assigns per dispatch batch (see `references/method-b.md`). Then `run-ops --phase update --run-phase launched` and send it under the coordinator token (the hints replace) |
| 6a Launch A | `Workflow({name: "task-orchestrator:implement-wave", args: <the bound plan's args object, passed as a real object — never a JSON-encoded string>})`. Record the returned `taskId` **and** the Workflow runtime's own run identifiers — `state.wfRunId` and `state.wfScriptPath` — from that call's result in `<scratchpad>/run-wave/<runId>/state.json`; these are distinct from the TO plan id (`r-…`) and the server run id, and are what a later resume needs to reattach to this exact Workflow run. **End the turn here** — the result arrives later as an async task notification; say so explicitly to the user |
| 6b Launch B | Run the Method B loop — see `references/method-b.md` |
| 7 Post-run | Runs only when triggered by the Method A notification, or by Method B's own loop reaching `next → {complete: true}`. See `references/post-run.md`: usage heartbeat, the end-seats sweep, verify, the notes audit, the batched advance, review hand-off, then `close` |
| 8 Review | Method B generic review (F-9 in the design) unless the project's `run-profile.json` sets `review: "handoff"`, in which case the run closes first and control passes to the project's own review step (this repo: `/implement` Step 5) |
| 8a Wave red-proofs | Once the wave's PRs have merged: the decision-point budget mutations for every item in the wave, batched in one scratch worktree at the merged SHA, each scoped to the test classes its `test-manifest` maps to, recorded once on the wave parent's `red-proof-results`. Background; never a merge gate. See `references/post-run.md` Step 7a |
| 8b Feature closure | A parent with 2+ children and a review phase: once every child is terminal, one feature reviewer over the merged range, every finding disposed in `orchestrator-confirmation`, then the parent completes. See `references/post-run.md` Step 7b |
| 9 Loop | Re-run from Step 2 without waiting for 8a. Items surfaced in the last coordinator `advance` answer's `unblocked[]` join the next frontier automatically |

Steps 1–5a are the **planning** half of a run; Steps 6–9 are the **execution** half. A run can
span many orchestrator turns — see turn-boundary discipline below.

---

## Stalled seats

`runs_read(select:"frontier", runId)` lists `inFlight[]` with `stalled` per seat (quiet strictly
longer than the run's `stallAfterSeconds`); the run view's `stalledSeats` counts them. A stalled
seat is handled like an interrupted one, never by raising a decision: snapshot its worktree's
uncommitted changes to `refs/wip/<short>` (as in the Resume matrix), re-`assign` the seat under the
coordinator token (this supersedes the old token and counts attempt n+1), write the new token to the
same token file, and re-dispatch the seat; its INTERRUPTED-ATTEMPT CHECK continues from the
snapshot. Past the run's attempt cap the `assign` answers `run_paused` (an `attempt-cap` decision):
stop and surface it.

---

## Turn-boundary protocol

This skill's loop is a protocol across turns, not a single-shot script:

- **Record the phase before every turn ends.** Whatever step you are mid-way through, the run's
  `hints.phase` (`run-ops --phase update --run-phase <phase>`, coordinator token) and the local
  `state.json` must reflect where you actually are, not where you expect to be next. Phases:
  `planned`, `launched`, `notified`, `post-run`, `review`. A turn that ends without recording it is
  a turn that resume (F7) cannot recover.
- **Increment `turns`** in `state.json` once per orchestrator turn in which you act for this run —
  this feeds the `orchestrator-turns` field of the provenance line (`references/post-run.md`).
- **Re-entry always goes through Step 0 F7, never from conversation context.** After
  compaction, a session restart, or simply picking the thread back up later, do not assume the
  run state you remember is still current — re-read the run view and the local state and resume
  from what they say. This is why the skill's own trigger phrases include "task notification from
  task-orchestrator:implement-wave" and "resume run r-…": both should land you back in Step 0,
  not mid-script.
- **A result whose `runId`/`serverRunId` matches no open run is reported, never acted on.**
  If a notification or Method B envelope references a run this session has no record of (already
  closed, or from a different session), surface it to the user as informational and stop —
  do not retroactively advance items based on it.

---

## Resume matrix

When Step 0 F7 finds an open run, read `runs_read` `get` (status, `hints.phase`, `pauseReason`),
`frontier` (`inFlight`, `ready`, `waiting`) and `seats` for it, and dispatch on the phase. A
**paused** run is surfaced first (its pause decision is a director's or a human's to answer); a run
whose coordinator token is lost (a new session) cannot be updated, assigned or closed with it:
close it with `run-ops --phase close --reason abandoned` under a human credential, then re-plan from
Step 2.

| phase | Resume action |
|---|---|
| `planned` | Re-validate with `node "<helper>" validate --in <scratchpad>/run-wave/<runId>/plan.json`, re-run the Step 5 parent check (idempotent) and REWRITE the pointer's `parent=` field with the current value (for example `parent=work` once the parent is already in work) rather than appending a second pointer line, then continue at Step 5a |
| `launched` (Method A) | Notification not yet seen — check `/workflows` or the task's status first. Before relaunching, protect interrupted work: for each run worktree that has uncommitted changes and no commit for the seat that was in flight (no `[<short>]` commit with a `Seat:` trailer in `<baseSha>..HEAD`), snapshot the changes to `refs/wip/<short>` without touching the index or the tree (temporary `GIT_INDEX_FILE`: `read-tree HEAD`, `add -A`, `write-tree`, `commit-tree -p HEAD`, `update-ref refs/wip/<short>`) and leave the tree as it is; the re-dispatched seat's INTERRUPTED-ATTEMPT CHECK continues from it. Re-`assign` every seat `frontier.inFlight` lists as stalled, or whose token file is missing, and rewrite its token file. If the session was itself restarted and the run is gone but `state.wfRunId`/`state.wfScriptPath` were recorded (Step 6a), stop the stale task (`TaskStop`) and relaunch with `Workflow({scriptPath: state.wfScriptPath, resumeFromRunId: state.wfRunId})`; if those keys are missing, close the run (`--reason abandoned`) and re-plan from Step 2 instead (seats are rerun-safe — re-dispatching an already-entered seat is not destructive) |
| `launched` (Method B) | Before relaunching, protect interrupted work: for each run worktree that has uncommitted changes and no commit for the seat that was in flight (no `[<short>]` commit with a `Seat:` trailer in `<baseSha>..HEAD`), snapshot the changes to `refs/wip/<short>` without touching the index or the tree (temporary `GIT_INDEX_FILE`: `read-tree HEAD`, `add -A`, `write-tree`, `commit-tree -p HEAD`, `update-ref refs/wip/<short>`) and leave the tree as it is; the re-dispatched seat's INTERRUPTED-ATTEMPT CHECK continues from it. Then resume `next` from the saved local `state.json`; any in-flight stage with no recorded `stage-result` is re-assigned and re-dispatched (rerun-safe) |
| `notified` / `post-run` | Continue `references/post-run.md` at the first step not yet recorded in `state.json` |
| `review` | Continue at Step 8 above |

**Warning on re-plans:** an `unblockAt: work` in-run edge holds for the first launch only. Once the blocker has entered work, a re-plan reads the edge as satisfied and admits the dependents with no wait. Finish the blocker in its own run before re-planning its dependents.

A `closed` run is finished — `runs_read` `list` with `status: "open"` never returns it, and a stale
pointer line in `session-tracking` pointing at a closed run is stale prose, not a live run.

---

## See also

- `references/snapshot.md` — the `run-wave/snapshot-v1` field reference Step 2 fills.
- `references/method-b.md` — the direct-Agent-dispatch execution loop used when `Workflow` is
  unavailable or Step 0 selects Method B, including scheduling, per-batch seat assignment,
  envelope handling, the declarations scan, and the degradations Method B declares relative to
  Method A.
- `references/post-run.md` — the post-run protocol shared by both methods: usage, the end-seats
  sweep, result verification, the notes audit, note-filling, the batched `advance` call, review
  hand-off, close, and the human checkpoints that close out a run.
- `/status-progression` — for a single item's own advance/gate state outside a run plan.
- `/ralph` — for headless, unattended queue draining instead of an interactive run plan.
- `/session-retrospective --run <runId>` — the retrospective scoped to this run's members (pass the
  server run id).
- `/implement` — the project-specific workflow this skill is invoked from for Parallel-tier and
  multi-item Delegated-tier waves.
