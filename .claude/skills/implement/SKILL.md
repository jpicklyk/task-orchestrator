---
name: implement
description: End-to-end workflow for taking MCP work items from backlog to merged PR. Handles git branching, schema-driven planning, implementation, independent review, and PR creation. Composes spec-quality, review-quality, and schema-workflow skills into a single pipeline. Use when a user says "implement this", "work on this item", "fix these bugs", "pick up the next task", "create a PR for this", "go through the backlog", or references specific MCP item IDs for implementation.
user-invocable: true
---

# Implement

End-to-end workflow for taking MCP work items from queue to PR. This skill composes
the schema-driven planning (spec-quality), implementation, review (review-quality),
and git/PR workflow into a single pipeline.

**Usage:**
- `/implement <item-id>` — work on a specific item
- `/implement` — with context about what to work on

---

## Step 1 — Assess the Work

Load the item(s) and determine the execution tier and interaction mode.

Call shapes are in `claude-plugins/task-orchestrator/skills/_generated/call-shapes.md` and field semantics in `current/docs/api-reference.md`; ids below are 8-hex shorts (any id field also takes the full UUID). Read every answer through the envelope: `ok`, then `data.results[i]` (`ok` or `error`). When a write element fails, the call-level `error` is only `partial_failure` (no detail, no fix): read `code`, `kind`, `detail` and `fix` from `data.results[i].error`. A read (`select`) call reports its failure in the call-level `error`. A dispatch-issued seat token goes in the top-level `seatToken` of every call, reads included; it is never printed, committed or written to a note.

For each item, call `items_read` `context` (`itemId`) to understand:
- Current role and gate status (`gateStatus.canAdvance`, `gateStatus.missing`)
- Item type (feature-implementation, bug-fix, etc.)
- Existing notes already filled
- Dependencies and blocked status

When the user or an item references a PR or commit as already landed, verify its merge state
before relying on it — `gh pr view <n> --json state,mergeCommit` plus `git log origin/main` —
and confirm the working tree is current (Step 2, base-freshness precondition) before any pass
that checks whether a change is present in the codebase.

**Execution tier** — classify by this table (canonical source shared with the `task-orchestrator:orchestrate` skill; edit the fragment, not this copy):

<!-- BEGIN GENERATED:tier-classification | source: claude-plugins/task-orchestrator/_fragments/tier-classification.md · regen: node claude-plugins/task-orchestrator/_fragments/generate.mjs -->
| Criteria | Tier | Pipeline |
|----------|------|----------|
| 1-2 files, known fix, no migration/new API | **Direct** | Orchestrator edits, tests, reviews inline |
| 3-10 files, single logical unit, clear or explorable scope | **Delegated** | Single subagent, separate review agent |
| 11+ files, multiple independent work streams, dependency edges | **Parallel** | Worktree agents, full pipeline |

**Force-UP signals** (bump tier regardless of file count):
- Database migration → min Delegated
- New public API surface → min Delegated
- Multiple independent work streams → Parallel
- User says "let's plan" / collaborative language → min Delegated

**Force-DOWN signals:**
- User says "just fix it" / "quick" → Direct (unless complexity contradicts)
- The item's type resolves to the `default` schema, or the item is schema-free -> eligible for Direct
<!-- END GENERATED:tier-classification -->

Delegated with ≥2 ready items, or Parallel → run `/task-orchestrator:run-wave`.

If the item's type has no schema, give it `quick-fix` for Direct tier; for Delegated/Parallel the `default` schema (when configured) catches it.

**Traits, before any dispatch.** A `traits` update replaces the list and re-pins the item, so send
every trait the item carries (`items_write` `update`, or `traits` at item creation):

```to-call
{"tool":"items_write","op":"update","args":{"id":"4b7d0e19","traits":["delegated"]}}
```

- `delegated` - Delegated or Parallel tier. Feature-implementation, feature-task and bug-fix carry
  it by default; apply it by hand to other types (`config` `effective` lists the declared traits).
  It makes the orchestrator-filled `delegation-metadata` note schema-visible. Never on Direct.
- `complex-implementation` - implementer on opus: new public API, cross-layer seams, subtle state
  machines or concurrency.
- `high-stakes` - planner and reviewer on fable, sparingly: security predicates, auth, data-loss
  paths, hard-to-reverse design. `needs-security-review` does not imply it.
- `needs-test-author` (the trigger rule) - bug-fix carries it by default. On a `feature-task`, apply it when acceptance
  criteria involve a predicate, algorithm, parser, validator or state transition, or on a force-ON
  signal (new public API, a migration, a security predicate, a prior vacuous-test finding in the
  area). No other type carries it. Direct tier: bug-fix only, in its **temporal-only degraded
  mode** (the `test-plan` gate and red-first still apply, `test-manifest` declares one actor, no
  separate dispatch).

Seat models come from the resolved `dispatchBySeat` (`items_read` `context` or `schema`); pass its
`model` and `effort` on every dispatch.

**Interaction mode** — orthogonal to tier:

| Signal | Mode |
|--------|------|
| User says "work with me on", "let's plan", or similar collaborative language | **Collaborative** — user participates in planning and key decisions |
| Scope is clear, no user participation needed | **Autonomous** — agent handles the pipeline |
| Unclear scope or ambiguous complexity | **Ask the user** |

When processing multiple items, evaluate whether related items (e.g., bugs in the
same module, fixes that touch the same files) should be grouped into a single branch
and PR. Group when the changes are cohesive and independent fixes would create
merge conflicts. Keep items separate when they're unrelated or when isolation makes
review cleaner. A batch of unrelated bug fixes is not a Parallel-tier feature: each fix gets its
own branch and PR (run-wave per-item mode); the shared worktree applies only to items under one
parent feature.

---

## Step 2 — Prepare the Branch and Worktree

Sync local main before any implementation begins.

```bash
git checkout main
git pull origin main --tags
```

**Base-freshness precondition.** Before creating any branch or worktree, and before any
source-verification pass (a grep or file read that decides whether a change is present), run
`git fetch origin` and compare `git log --oneline -1 origin/main` with `git rev-parse --short HEAD`.
The two SHAs must match, or HEAD must be a descendant of `origin/main` on the intended branch.
**A source-verification pass must never run against a working tree that has not been confirmed
current** — this includes the orchestrator's own session worktree. If the tree is behind, read
the file directly from the remote (`git show origin/main:<path>`, from PowerShell on Windows —
the Bash tool's MSYS layer mangles the `ref:path` colon) or create a fresh worktree from
`origin/main` (fallback block below). A stale base makes adopted work look missing.

The branching/worktree strategy depends on tier (layout, dispatch patterns, shared-index commits
and review scoping: [WORKTREE.md](WORKTREE.md)):

**Direct tier** (orchestrator implements 1–2 files inline) — create a working branch on the main directory:

```bash
git checkout -b <branch-name>
```

**Delegated tier** (single subagent) — same as Direct: orchestrator creates the branch on the main directory, the subagent works against it. No worktree.

**If the main checkout is unavailable** (another branch checked out, uncommitted changes present)
**or the orchestrator itself runs from a worktree** — Direct and Delegated tiers both: do not touch
the occupied checkout. Create a dedicated worktree instead:

```bash
git worktree add .claude/worktrees/<slug> -b <branch-name> origin/main
```

Work there using absolute paths and `git -C` (never `cd`); after the PR merges, remove the worktree
and delete the branch. Sync local `main` via `git fetch origin main:main` while `main` is not
checked out anywhere, or a normal `git pull` from the main checkout when it is.

**Parallel tier** (parent feature with multiple children) — create a **single feature worktree** that all child agents share:

```bash
FEATURE_SLUG=<slug>
FEATURE_BRANCH=feat/$FEATURE_SLUG
FEATURE_WORKTREE=.claude/worktrees/feat-$FEATURE_SLUG

# Resume detection — if the branch/worktree already exist (orchestrator restart
# mid-feature), reuse them rather than recreating:
if git show-ref --verify --quiet "refs/heads/$FEATURE_BRANCH"; then
  echo "Resuming existing feature branch $FEATURE_BRANCH"
else
  git branch "$FEATURE_BRANCH" main
fi
if [ ! -d "$FEATURE_WORKTREE" ]; then
  git worktree add "$FEATURE_WORKTREE" "$FEATURE_BRANCH"
fi
```

All child-task agents will be dispatched into this **shared** worktree (Step 4). The feature branch is pushed and PR'd **once**, when the parent feature reaches terminal (Step 6).

**Plan file as dispatch contract (every Parallel wave).** Before the first dispatch, instantiate
[`references/dispatch-contract-template.md`](references/dispatch-contract-template.md) at
`<main-checkout>\plans\<slug>.md` and fill every slot (the template's `n/a` form for one that does not apply).
`plans/` is gitignored and absent from the worktree, so always hand out the absolute path recorded
in the Header's Contract path line. Prompts point at a slot and never paraphrase it: agents read
only their prompt and their schema guidance, so a rule left in skill prose never reaches them.
Under run-wave, seat prompts are generated and do not read the file; it then serves the
orchestrator (fallback dispatches, the commit map) and the reviewers. Run-wave reuses this
worktree with `--mode shared --worktree $FEATURE_WORKTREE --branch $FEATURE_BRANCH`. The contract
makes execution free of deviation, not the plan correct: Step 3's "plans are spec inputs" covers that.

**Branch naming:**
- `feat/<feature-slug>` — feature-implementation parents (the integration branch)
- `fix/<short-description>` — bug-fix items (Direct or Delegated tier)
- `fix/<grouped-description>` — batch of related bug fixes (Delegated tier)
- `chore/<short-description>` — tech debt, refactoring (Direct tier)

---

## Step 3 — Queue Phase: Planning

This step is **tier-conditional**:

**Direct tier:** Skip this step entirely. No plan mode. No queue-phase notes. Call
`advance` `transition` with `trigger: "start"` immediately to move queue→work. The `quick-fix`
schema has no queue-phase required notes, so the gate passes. **Exception:** if the item
carries `needs-test-author` (temporal-only degraded mode, Step 1), `test-plan` is a
queue-phase note and must be filled before this advance — see the seat-timing rule below.

**Delegated tier:** Fill queue-phase notes per schema. Use `items_read` `context`
and `items_read` `schema` with `fields: ["guidance"]` to see the expected notes and their guidance text (`guidanceKey` is the key of the first missing required note). Pre-plan-workflow is optional - use
only if scope needs exploration. Post-plan-workflow only if child items need
materialization. Advance: `advance` `transition` with `trigger: "start"`.

**Parallel tier:** Full planning pipeline. The two interaction modes differ only in who enters
plan mode:

1. **Collaborative:** ask the user to enter plan mode; the `pre-plan-workflow` and
   `post-plan-workflow` hooks fire on plan mode entry and exit (context gathering,
   materialization). **Autonomous:** read and follow the `pre-plan-workflow` skill yourself
   (existing MCP state, schema requirements, the definition floor), then research the codebase.
2. Fill every queue-phase note following its guidance text (`items_read` `schema`,
   `fields: ["guidance"]`); the guidance routes to spec-quality where it applies, in either mode.
3. After plan approval, materialize child items if the plan calls for them (`post-plan-workflow`:
   the exit hook in collaborative mode, by hand in autonomous mode).
4. Advance the item: `advance` `transition` with `trigger: "start"`.

The gate will reject advancement if required notes are missing (`gate_blocked`, with
`data.results[0].error.detail.missing[]`). If rejected, fill the missing notes and retry.

**Plans are spec inputs.** Every file path, tool contract, or call sequence a plan names must
cite its authoritative location and be verified on disk or in source before the plan is
approved — child items' specification notes are generated from the plan and inherit its errors
silently (spec-quality, "Cite Contracts, Don't Restate Them"). This reminder lives here because
spec-quality is reached via note `guidance` pointers that plan mode does not traverse.

**Plans carry the State matrix.** For each item that changes user-visible runtime behaviour, the
plan's Verification / Test strategy section carries (or points at) the item's State matrix, or
one `State matrix: n/a - <reason>` line (spec-quality, "State matrix"). Same reason as above:
plan mode does not traverse the guidance pointer that would surface it.

**Do not confuse this with resource-lease contention.** A queue→work `advance` can also fail
`resource_unavailable` (`error.kind: "transient"`): a resource a trait on this item declares
(`resources:`) is held by another item. Filling notes will not fix it. Work a different item and
retry later (`error.retryAfterMs` is a hint), or report the keys in `error.detail.resources[]` to the user.

**Seat timing for `needs-test-author` items.** The queue-phase `test-plan` note is filled by the
**planning seat** (the orchestrator on Direct tier, the plan author on Delegated/Parallel), using
the `test-author` skill's scenario- and oracle-derivation sections, **before** the queue-to-work
`start`: it gates work entry like any required queue note. It is not Step 4b's **test author**
seat, which writes test code and fills `test-manifest` at work phase.

**Planning seat (Parallel tier, one `planning`-class agent per stream, after materialization and
before any implementer).** Under run-wave this is the `planner-v1` stage, and its return is
schema-checked. By hand: give it the stream's `diagnosis`/`task-scope` note, the current codebase
and the contract's absolute path. It verifies every claim against source (`file:line`), writes or
revises the note, freezes `test-plan`, and returns the fields in WORKTREE.md "Planner return
fields". Its return fills the contract's File ownership slot. Never trim a field to fit a length:
the fields are the budget.

---

## Step 4 — Work Phase: Implementation

**Verification.** "Run tests" means `:current:test` AND `:current:ktlintCheck` (CI enforces both).
The orchestrator runs them through the lock helper, which serializes gradle and prints a final
`EXIT=<n> (log: <path>)` line. Read that line, and read the log when it is non-zero:

```
$env:TO_GRADLE_OWNER='orchestrator'; & "<wt>\.claude\skills\implement\references\gradle-locked.ps1" -Worktree "<wt>" -Tasks ":current:test",":current:ktlintCheck"
```

(PowerShell tool; this is `run-profile.json` `wave-build`; `<wt>` is the main checkout or the
item's worktree on Direct/Delegated.) Without the helper: redirect gradle to a log, capture
`EXIT=$?`, then read the log. Never pipe gradle into `tail`: the pipe reports the pager's exit
code. On a lint failure run `:current:ktlintFormat`, then re-run both.

| Check | When | Who |
|---|---|---|
| Pinned self-check (`ktlintFormat` + compile), once before committing | every writer seat | seat, through the helper |
| Own test classes, red then green (`-TestFiles`) | while writing them | test author |
| Changed packages' test classes (`-TestFiles`), at most once | before committing | implementer |
| Full suite + `ktlintCheck`, incremental | once per wave or item, after the last writer commits, before any PR | orchestrator |
| `--rerun-tasks` | only the run after a dependency or Gradle upgrade | orchestrator |
| Bug-fix revert, coupled-authorship red checks | per item, before merge | orchestrator |
| Budget mutations | after merge (Step 6) | orchestrator |
| Reviewer | reads the recorded build state; runs nothing (a doubted red-proof is re-run scoped) | — |

The helper refuses the suite, `ktlintCheck` and `--rerun-tasks` from any seat (EXIT=98), so a seat
prompt never carries a suite command: point it at the contract's Compile self-check slot (Parallel)
or paste `run-profile.json`'s seat `verify` entries (a hand dispatch, per `orchestrate` "Hand-dispatched
seats carry the project's verify commands"). The helper's header explains a held or reclaimed lock
(`LOCK HELD`, `LOCK RECLAIMED`).

This step is **tier-conditional**:

**Direct tier:** Implement directly. Edit the files, run the test suite. No subagent
dispatch. No `/simplify` pass. **Exception — a Direct-tier `bug-fix` carrying
`needs-test-author`:** follow Step 4b's Direct-tier test-first-then-fix sequence
(test-plan frozen at queue → regression test observed red → fix → green) instead of
implementing first. Fill the `session-tracking` note (required by both
`quick-fix` and `default` schemas) as a delta record per its schema guidance: outcome, the
`Commits:` line, the `Verified:` line, deviations and friction (for `quick-fix`, one short
paragraph on what the fix was and why that way, then those lines). No file list and no test
counts. Advance: `trigger: "start"` to review, or `trigger: "complete"` to terminal when the
schema has no review phase (`quick-fix`); Step 5 reads `newRole`.

**Delegated and Parallel tiers:** Use `items_read` `schema` (`itemId`) to see the work-phase
notes, with `fields: ["guidance"]` to read their guidance text. Fill each required note following its
guidance. Follow `/task-orchestrator:orchestrate` (model table, return
formats, UUID inclusion). The key decisions at this step are:

- **Single item (Delegated):** delegate to one implementation subagent or implement
  directly. Subagent works in the main directory on the working branch.
- **Multiple child tasks, independent (Parallel):** dispatch parallel subagents into the
  **shared feature worktree** created in Step 2. Each agent receives the worktree path
  and branch name. Do **not** use `isolation: "worktree"` on the Agent tool — that would
  spawn a separate worktree per dispatch, which is the deprecated per-child PR pattern.
- **Multiple child tasks, dependent** (a semantic dependency; sharing a file alone is not one): dispatch sequentially into the shared feature
  worktree. Wait for each agent's commit to land before dispatching the next.

**Under `/task-orchestrator:run-wave`, both bullets above are executed by the run plan** (Method A
via `Workflow`, Method B via `next`-scheduling), reading each stage's `dispatchBySeat` entry for
model, effort and agent. The hand-dispatch form remains the fallback for dispatches a run plan
doesn't cover — a one-off fix agent, or an arbitration re-dispatch (Step 4b) — not the default
path for a Parallel-tier wave.

**Test-file ownership boundary.** Every implementation dispatch (Delegated single agent or
Parallel per-child agent) excludes `src/test/**` from scope — implementers do not create or
modify test files. When a change surfaces a needed test update, the agent reports it in its
return (or in `implementation-notes`) rather than editing the test itself; the test author
(Step 4b) owns that file tree exclusively on items carrying `needs-test-author`.

**Dispatching by hand?** Read [WORKTREE.md](WORKTREE.md) "Hand-dispatching a Parallel wave" before
the first implementer dispatch: it holds the implementer and test-author prompt templates, the
planner return fields and the SHA tracking table.

**File-edit overlap discipline:** Parallel agents in a shared worktree must operate on
non-overlapping files. The orchestrator scopes each agent's prompt to a specific file list
(the dispatch contract's File ownership slot carries the rule). When inherent overlap exists, under run-wave declare the file in both scopes (`Shared file, lock-serialized: <path> (with <other short>)`) and add no BLOCKS edge; a hand-dispatch still dispatches sequentially, without an edge.

**Contract-change sweep discipline (orchestrator-owned, after the implementers return and before
build verification).** When a change tightens a contract (a now-required parameter, a new
`validate()` invariant, a new sealed arm, a new accessor on a strict-mocked interface such as
`RepositoryProvider`), follow served rule `contract-change-sweep` (`docs_read` `get`, `kind` `rule`):
search `src/main` and `src/test`, repair construction only (never an assertion), and declare each
repair SHA in the review handoff (the Review scoping commit map). A repair that would touch an
expectation goes to the test author. When a fix changes shipped behaviour after docs were written,
grep the changed docs for claims about the old behaviour before finalizing.

**Model and effort.** Pass both explicitly on every Agent dispatch (`dispatch-model-guard` denies an
omission): under run-wave from `dispatchBySeat`; otherwise follow `task-orchestrator:orchestrate`
"Dispatching an item's phase owner" (read the profile of the phase dispatched INTO).

**After implementation agents return:** record each agent's pre/post SHA (and the test author's,
Step 4b) in the tracking table (WORKTREE.md). In a shared worktree a range interleaves other
streams' commits and misses later fix-ups, so it confirms ownership and feeds the Review scoping
commit map; it never bounds a review.

**Disjointness check (required whenever `needs-test-author` applies).** After both ranges are
captured, verify:

```bash
git -C <feature-worktree> diff <pre-sha>..<post-sha> --name-only          # impl range
git -C <feature-worktree> diff <test-pre-sha>..<test-post-sha> --name-only # author range
```

- impl-range ∩ `src/test/**` = ∅ (the implementer touched no test files)
- author-range ∩ `src/main/**` = ∅ **and** author-range is non-empty (the author touched only
  test files, and touched at least one)

A violation is reverted (`git -C <feature-worktree> revert` the offending commit, or a targeted
`git checkout` of the crossed-boundary file back to the prior SHA) and recorded in the item's
`orchestrator-confirmation` (the orchestrator never writes `implementation-notes`) — do not
silently keep a cross-ownership commit.

---

### Step 4b — Test Authoring

Applies only to items carrying the `needs-test-author` trait. Runs after implementation agents
return (their commits exist on the branch/worktree) and before the orchestrator's build
verification below (Direct tier inverts this ordering — see the Direct-tier bullet below) —
the test author compiles against the real implemented surface, not a
predicted one. **Two distinct seats, do not conflate them:** the **planning seat** already froze
`test-plan` at queue phase, before this step, gating work entry (see Step 3's seat-timing rule);
the **test author** seat here writes test CODE and fills `test-manifest` — it does not fill or
re-open `test-plan`.

**Per-tier sequencing:**

- **Direct tier:** temporal-only degraded mode only (see Step 1) — no separate agent, and on a
  bug-fix the sequence is **test-first-then-fix**, not "tests after implementation":
  (a) fill `test-plan` at queue phase, before any implementation begins;
  (b) write the regression test from the `diagnosis` note's reproduction steps and **observe it
  red against the pre-fix code**, recording the red evidence (assertion, observed value) in
  `test-manifest`;
  (c) implement the fix;
  (d) confirm the test is green post-fix and record that confirmation too.
  `test-manifest` declares the single seat, and the disjointness check (Step 4) does not apply (one
  seat, one range) — see Step 5's verdict rule for how this tier is scored.
- **Delegated tier:** one additional **sequential** dispatch on the same branch, after the
  implementation agent's commit lands. The test author runs the pinned self-check (`ktlintFormat`
  plus compile, `run-profile.json` `compile-self-check`) once before committing, and ONLY its own
  test classes (`-TestFiles`) to see them red and then green; never `:current:test` or
  `:current:ktlintCheck`; the orchestrator runs the suite once before review.
- **Parallel tier:** one test-author agent **per child**, dispatched as a dedicated wave between
  the implementation wave and the orchestrator's build verification (below). Authors in this
  tier do NOT run `:current:test` or `:current:ktlintCheck` — only `ktlintFormat` plus a compile
  self-check, mirroring the implementation dispatch template, since the orchestrator owns full
  build verification for the wave.

**Seat rules.** Blindness, oracle provenance and the `test-manifest` fields are the test author's
own rules (served rule `test-author` sections 3, 4 and 10; `declarations-extractor` for the declarations
block, which a separate seat fills and the orchestrator scans for implementation prose; under
run-wave the scan is the `scan-declarations` step in Method B or the run script's
`scanDeclarations` call in Method A). Invoke the `test-author` skill before `test-manifest` is
filled. Hand-dispatch prompt: the contract's Test author protocol slot (paste it verbatim on
Delegated, where no contract file exists); see WORKTREE.md. Capture Test-Pre-SHA and
Test-Post-SHA, then run the disjointness check (Step 4).

#### Arbitration — Red Author-Authored Tests

When a test the author wrote comes back red against the implementation, the test author does not
decide who is at fault (`test-author` §8/§9) — it reports. The **orchestrator arbitrates**, with
exactly three dispositions:

1. **Implementation wrong.** Re-dispatch the implementer, scoped to `src/main/**` only.
2. **Test wrong.** REQUIRES a spec citation before this disposition is available: the note key
   plus the quoted acceptance criterion plus the asserted-vs-observed values. "The implementation
   does X" is not evidence for this case — it is evidence for case 1. If no covering criterion
   exists to cite, this disposition is not available; fall through to case 3. Before ruling case 2,
   confirm the failing test's INPUT fixture matches the declared input contract (the declarations
   file or the producing stage's schema, e.g. planner-v1): a fixture-shape mismatch is a fixture fix
   on the test side that keeps the oracle, not a case-2 oracle change. A case-2 fix keeps the S-id's
   oracle assertion; name the oracle in the re-dispatch prompt. Once cited, re-dispatch the test
   author, scoped to `src/test/**` only.
3. **Spec wrong or silent.** Escalate to the user. Amend `task-scope`/`diagnosis` first, then
   dispatch whichever side the amendment implicates.

**Record every arbitration**, and every other orchestrator ruling in a fix cycle, in the child's
`orchestrator-confirmation` (orchestrator-owned; under the coordinator token while the child is a
member of an open run): one line per ruling, `A<n>: case <1|2|3> - <the citation, for case 2> -
<resolving sha>`, above the disposition lines. The author's own report of the red test stays in its
`test-manifest`. Read the current body first (`notes_read` `get`) and upsert it whole; never drop or
rewrite an earlier line, and mark a reversed one `REVERSED by A<m>` in place. **Never weaken, skip, or `@Disable` a
test to unblock a wave**, regardless of disposition; that is exactly the failure mode this trait
exists to prevent (`test-author` §7, §9).

This carve-out also modifies the build-verification rule below: a red author-authored test is
**not** a broken build. Route it to arbitration above; if unresolved, hold the child in work and
report — do not dispatch a generic "fix agent" against it.

---

### Step 4c — Build Verification and Wrap-Up

**Build verification (orchestrator-owned, serialized).** After the last writer seat of a
parallel batch commits (or between sequential children), confirm a clean tree
(`git -C <feature-worktree> status`) and run Step 4's "Verification" command with `<wt>` the
feature worktree (Direct/Delegated: the working branch's checkout). This is the wave's ONE
full-suite run: incremental (`--rerun-tasks` only after a dependency or Gradle upgrade), before
any PR opens. Do not re-run it per seat return, and do not let a seat run it for you.

A failure means a recently-committed child broke something. Dispatch a fix agent
(same shared worktree) before continuing. Do **not** advance any child to review
until the build is green, and never retry until green: a retry hides a real bug.

**Carve-out: a red author-authored test is not a build failure.** On items carrying
`needs-test-author`, a test failure traced to a test the test author wrote (Step 4b) is not
"the build broke" — do not dispatch a generic fix agent against it. Route it to the
Arbitration subsection in Step 4b instead. If arbitration doesn't resolve before this
verification pass needs to conclude, hold the child in work phase and report the unresolved
red test rather than forcing green through a fix-agent dispatch.

**For Delegated tier** (single subagent), the agent commits to the working branch
on the main directory. Capture the changed files via
`git diff main --name-only` and proceed.

**Post-implementation steps** (run in the feature worktree for Parallel tier, or on
the working branch for Direct/Delegated):

1. Run the `/simplify` skill on the changed code to check for reuse, quality, and
   efficiency — this is a cleanup pass before review, not a review itself
2. **If `/simplify` made changes**, the resulting test coverage work follows the
   ownership boundary above. On items carrying `needs-test-author`, re-dispatch the
   test author to write or update the covering tests (Step 4b) — the implementer/orchestrator
   does not touch `src/test/**` even for a simplify-driven update. On items without the trait,
   write or update tests inline as before. Either way, the simplify pass is still part of the
   work phase — all code changes require test coverage before advancing to review.
3. **Log findings as work items** — any issues surfaced by `/simplify` or during
   implementation that are not immediately addressed (pre-existing tech debt,
   optimization opportunities, related bugs) must be logged via
   `/task-orchestrator:create-item` before moving on. Do not discard findings.
4. **CHANGELOG bullet** — written here, in the work phase, so the
   reviewer sees it. A user-visible change — server behaviour, the MCP or REST surface, config
   keys, plugin skills or hooks — adds ONE bullet under `CHANGELOG.md`'s `## [Unreleased]`
   section, in its `### Added` / `### Changed` / `### Fixed` subsection (plugin skill and hook
   changes go under `### Plugin`). The bullet describes behaviour, not the diff, and carries no
   volatile counts. A docs-, process- or chore-only change adds none; its PR body will state
   `Changelog: none (<why>)`. Direct/Delegated: committed with the change. Parallel: the
   orchestrator or docs seat writes it once, after the implementation wave and before review —
   never an implementer (the dispatch contract's **Docs** slot). `/prepare-release` Step 8c folds
   these bullets into the release section.
5. Fill all work-phase notes following their guidance text (`items_read` `schema`,
   `fields: ["guidance"]`) - focus on context
   that downstream agents need to know
6. **Live-owed check** - before the work-exit `advance`, read the
   item's `session-tracking` note for its `**Live-owed**:` line (spec-quality, "State matrix").
   Any entry not ending `run <date>: <result>` or `deferred: <item id>` is open and holds the
   item in work until it is run or deferred. The orchestrator writes that line from the spec
   note's matrix when it first fills `session-tracking`; a matrix with `live-owed` cells and no
   `**Live-owed**:` line counts as an open entry. This check sits at work exit because
   `feature-task` items skip review by default (Step 5).
7. After implementation completes, the orchestrator advances the item out of work (a delegated
   agent returns after filling its work-phase notes; on Direct the orchestrator fills them itself):
   `advance` `transition` with `trigger: "start"` when the schema has a review phase, or
   `trigger: "complete"` when it has none (run-wave `references/post-run.md` Step 4 uses the same
   rule). Inspect `newRole` in the response to determine what comes next (see Step 5).

---

## Step 5 — Review Phase

Before dispatching or performing review, check the item's current role. Inspect
`newRole` from the `advance` result (`data.results[0]`) in the previous step:

- **If `newRole` is `terminal`:** The item's schema has no review phase (lightweight
  lifecycle). Review dispatch is not needed — the item completed through its natural
  lifecycle. Proceed to Step 6. Note: `feature-task` items skip review by default
  (work→terminal directly) — the `needs-task-review` trait re-enables a review phase
  for a specific child when needed.
- **If `newRole` is `review`:** Continue with review per tier below.

This step is **tier-conditional**:

**Direct tier:** Perform an inline review. Read the diff, verify correctness, confirm
tests pass. If the review-phase note has a `skillPointer` (visible in the `advance`
result or via `items_read` `context`), invoke that skill for the evaluation framework before
filling the review note. Write the review note, then advance to terminal:
`advance` `transition` with `trigger: "complete"`. No separate review agent —
the overhead exceeds the risk for 1-2 file changes with known fixes. This inline,
orchestrator-written review note applies only to items outside any run: Direct tier never runs
under a run, and on a run member a seat-owned review key belongs to the reviewer seat.

**Delegated and Parallel tiers:** Dispatch a **separate** review agent. The agent
that implemented the code must not review its own work.

**Reviewer scoping for Parallel waves.** Per-child reviewers remain the default for code-bearing
waves. When every child in the wave is content-only (config, skill, or doc edits — no `src/main`
or `src/test` changes) AND the wave shares a pinned plan-file contract (Step 2), dispatch ONE
consolidated `opus` reviewer over the full feature-branch diff (`git diff main...<FEATURE_BRANCH>`)
instead of N per-child reviewers. Cross-file coherence defects — sibling skills stating
contradictory rules, gate placement contradicting seat timing, an example contradicting its own
rule — are visible only to a reviewer holding the whole diff; per-child reviewers structurally
cannot see them, and a shared naming contract does not prevent them. The consolidated reviewer
fills the review-phase `review-checklist` note of each reviewed CHILD (one note per child, all
under the same reviewer actor). When the children's schema has no review phase (the
`feature-task` default), this Step 5 consolidated pass is skipped. Either way the parent feature's
`review-checklist` is never filled here: the parent is reviewed once, in Step 6 item 3 (`run-wave`
`post-run.md` Step 7b), after every child is terminal.

**If the implementation used the shared feature worktree** (Parallel tier), the
review agent operates in that worktree, scoped to **that child's owned files** — the diff of
those paths from the wave's base SHA to current `HEAD`, per the contract's **Review scoping**
slot. Not the `<pre-sha>..<post-sha>` range captured during dispatch: in a shared worktree that
range interleaves other streams' commits and excludes any later fix-up, formatter or
fixture-repair commit to the same files, so it both over- and under-reports. The pre/post map
still serves the ownership two-range check (list item 6 below).

**Review agent template (copy verbatim, fill placeholders):**

```
You are reviewing one child task within a shared feature worktree.

- Feature worktree: <FEATURE_WORKTREE_PATH>
- Feature branch: <FEATURE_BRANCH>
- Dispatch contract: <ABSOLUTE path from its Header "Contract path" line, e.g.
  D:\Projects\task-orchestrator\plans\<slug>.md> — read it first; it wins on conflict
  with this prompt. It lives in the main checkout, not in the worktree above.
- This child's owned files (its row in the contract's File ownership slot):
  <FILE LIST>
- Your review scope is the diff of exactly those files:
  git -C <FEATURE_WORKTREE_PATH> diff <BASE_SHA>..HEAD -- <FILE LIST>
- Other children committed into this same branch, and later fix-up, formatter or
  fixture-repair commits may touch these files. Review the owned-file diff above as it
  stands now; do NOT review another child's files, and do NOT bound your review by a
  commit range.

Run ALL commands from within the feature worktree.
Read ALL files from that directory. Do NOT read from the main working directory.

Tests have already been verified green by the orchestrator after the most recent
commit batch. Run no gradle — the contract's Compile self-check slot states who runs
it, and the Review scoping slot records the build state you rely on. Focus on plan
alignment, test quality, and simplification per the review-quality skill.

Report every finding at every severity — do not self-filter to a high-severity-only
bar. Mark each finding blocking or observation and state your confidence; the
orchestrator's verdict handling is the downstream filter, not your own judgment.
```

**Label and seat parts.** Dispatch every reviewer with the Agent `description` `reviewer:<short>` (the label
table is the "Label rule" of run-wave `post-run.md`; do not restate it). On a run-wave run, which is open under
`review: "handoff"` with a reviewer seat assigned per item (`post-run.md` Step 5), append to the template the SEAT
TOKEN and END SEAT parts that `node "<helper>" review-prompt --plan <plan> --item <short> --result <result>` renders for
a bound run: copy those two paragraphs from its output and never write the token rules by hand. The consolidated
reviewer gets each child's reviewer token file (`tokens/<short>.reviewer.token`), passes the matching child's token on
that child's calls, and ends each child's seat. A consolidated reviewer is one Agent, labelled `reviewer:<short>` with
the short id of the first child in plan order: `provenance` looks a seat's meters up as `<seat>:<item short>`, so
`--extra-seats reviewer:<model>` goes on that first child only, and every other child's `delegation-metadata` carries,
from line 3 on, a prose pointer to that child instead. The session coverage counts the one label once.

**If using Direct or Delegated tier** (single working branch on the main directory),
the review agent reads from the working branch and runs no gradle itself (see item 4
below) - no worktree-specific scoping needed.

The review agent:
1. Reads the review-quality skill
2. Uses `items_read` `context` and `notes_read` `list` (`itemId`) to load the item's notes and review-phase requirements
3. Reads the changed files — on Parallel tier, the owned-file diff above; on Direct/Delegated
   tier, the working branch
4. Runs no gradle on any tier. In a Parallel wave the orchestrator already verified the wave and
   recorded the build state in the Review scoping slot. On Direct tier the orchestrator reviews
   inline after its own run. On Delegated tier the orchestrator runs `:current:test` and
   `:current:ktlintCheck` (Step 4's "Verification" - a PR with failing lint will not
   merge) and records the `Verified:` line (command, result, SHA) in its own `session-tracking`
   before dispatching the reviewer, which reads the build state from there per `review-quality` Area 1.
5. Evaluates plan alignment, test quality, and simplification
6. **On items carrying `needs-test-author`, additionally:**
   - **Two-range ownership check:** spot-check that `git diff <impl-range> --name-only` touches no
     `src/test/**` path and `git diff <test-range> --name-only` touches no `src/main/**` path. On a
     Direct-tier temporal-only item it is `N/A: temporal-only, single seat` and cannot fail.
   - **Temporal checks (Direct-tier temporal-only items only):** `test-plan` was frozen at queue
     phase, before implementation began (Step 3's seat-timing rule), and for a bug-fix the
     red-first evidence (Step 4b, Direct tier, (b)) is recorded in `test-manifest`.
   - **Arbitration-record check:** every arbitration has its line in `orchestrator-confirmation`
     (Step 4b), with the spec citation for a case-2 "test wrong" call (note key + quoted
     criterion + asserted-vs-observed).
   - **`assumeTrue`/`@Disabled` scan on author-owned files:** any weakening introduced *after* the
     author's first commit in this range is an automatic blocking finding.
   **Verdict rule:** `test-independence-audit` fails to `not-independent` (blocking) if any
   *applicable* check above fails, regardless of how green the test suite is. On a Direct-tier
   temporal-only item where every applicable check passes, the verdict is
   `independent-degraded (temporal-only)` — never plain `independent`, matching `test-author` §11.
7. Fills the review-phase notes per their guidance text (`items_read` `schema`,
   `fields: ["guidance"]`) with a verdict

**Handling the verdict:**

| Verdict | Action |
|---------|--------|
| **Pass** | Proceed to Step 6 |
| **Pass with observations** | Proceed to Step 6; dispose observations per Dispositions at child close (`fixed` only under run-wave `post-run.md` Step 5 "Observation triage") |
| **Fail — blocking issues** | Stop and report to the user with the full findings. Do not attempt to fix autonomously — bring the human into the loop. |

In a Parallel wave, a child's review failure does not stop its siblings (their commits are already
on the feature branch): continue them and report every failure together. Each failed child then
blocks parent finalization (fix and re-review), is descoped (cancelled), or has its commits reverted.

Once the user rules a fix on a run-wave item under `review: "handoff"`, the fix cycle takes the still-open-run branch of run-wave `post-run.md` Step 5 "Fallback" (re-assign the base seat, reseat with the run's own `plan.json`, and after the re-review rewrite `session-tracking` and re-upsert `delegation-metadata` as that Fallback's step 5 says, under the run's coordinator token); it opens no `<runId>-a<n>` run, and the numbered steps 1-6 apply only once the run has closed.

**Dispositions at child close.** When a child's review returns Pass or Pass with observations,
write the child's `orchestrator-confirmation` before advancing it: one line per O# as `fixed <sha>`,
`follow-up <item-id>` (the item created first) or `accepted: <reason>`, `N of N disposed`. Nothing
is left for the parent review to carry. `fixed` is bounded by run-wave `post-run.md` Step 5
"Observation triage": only a concrete failure in behaviour the item added or changed, one
`implementer-a<n>` (or `test-author-a<n>`) seat rendered with `reseat --cause review --findings
<ids>` and scoped to the finding ids, no planner amendment, no re-review unless the fix changes
behaviour beyond the finding; the Fail recipe (mechanical or ruling-needed) is for blocking
findings only.

Review failures surface issues that may indicate systemic problems worth learning
from. Automatically retrying hides these signals.

---

## Step 6 — Finalize and PR

The shape of Step 6 depends on tier.

### Direct and Delegated tiers — finalize per item

After review passes:

0. **Post-dispatch commit audit (non-blocking):** run `git log --oneline -3` and
   `git status --short` and compare against what the orchestrator itself committed.
   Any commit a subagent made despite stop-boundary instructions is flagged for
   review here — inspect its scope before it rides into the squash-merge (a
   subagent commit lacks the co-author trailer; reconcile at merge time). On items
   carrying `needs-test-author`, additionally check each commit's file list for
   cross-ownership: an implementer commit touching `src/test/**`, or a test-author
   commit touching `src/main/**`, is flagged here even if it slipped past the
   Step 4 disjointness check — do not let it ride into the squash-merge unreconciled.

1. Verify the working branch is committed (orchestrator commits if Direct tier;
   subagent committed if Delegated). Stage only the files related to the
   implementation:
   ```bash
   git add <specific-files>
   git commit -m "$(cat <<'EOF'
   <type>(<scope>): <description>

   <body — what changed and why, referencing the MCP item>

   Co-Authored-By: Claude <noreply@anthropic.com>
   EOF
   )"
   ```

   **Commit types:** `feat` for features, `fix` for bugs, `refactor` for tech debt,
   `perf` for performance, `test` for test-only changes, `chore` for maintenance.

2. **CHANGELOG check and Docs line.** Confirm the branch carries the `[Unreleased]` bullet written in
   Step 4c (post-implementation step 4), or that the PR body will state `Changelog: none (<why>)`.
   Both PR-body lines come from the diff, never from the plan: run
   `git diff --name-only <base>...HEAD` and (a) list every changed doc path from that output on the
   body's Docs line; (b) require `CHANGELOG.md` in that output unless the body states
   `Changelog: none (<why>)`. Recompute both lines after any merge round (a base merge or
   update-branch), before merge.

3. Push the working branch:
   ```bash
   git push -u origin <branch-name>
   ```

4. Create the PR:
   ```bash
   gh pr create --base main --title "<type>(<scope>): <description>" --body "$(cat <<'EOF'
   ## Summary
   <2-4 bullets>

   ## Test Results
   <test count, pass/fail, new tests>

   ## Review
   <verdict summary>

   ## Docs
   <every changed doc path from `git diff --name-only <base>...HEAD`>

   ## Changelog
   <the [Unreleased] bullet text, or none (<why>)>

   ## MCP
   <item ID>
   EOF
   )"
   ```

5. **Merge gate** (Parallel tier step 7 uses this same gate):
   - Wait on `gh pr checks <n> --watch --fail-fast` and squash-merge only when every check is
     green. Never merge with a check pending. If auto-merge is armed, GitHub enforces "all green"
     only for REQUIRED checks, so this gate still applies to the rest.
   - **Review-first arming.** Arm auto-merge (`gh pr merge <n> --squash --auto`), or merge, only
     after the item's review verdict is recorded with 0 blocking, and only once every observation
     the orchestrator chose to act on is committed on the branch. A PR opened before review (for
     early CI) is opened without auto-merge. If auto-merge is already armed when a review starts,
     disarm it first (`gh pr merge <n> --disable-auto`).
   - **Guard landing.** When a merged PR adds or tightens a guard or contract test
     (architecture/Konsist, a consistency test, a token or size budget), run
     `gh api -X PUT repos/<owner>/<repo>/pulls/<n>/update-branch` on every open PR against the
     same base, and let its CI re-run green before arming.
   - On a red check, read the failed job. An infra flake gets one
     `gh run rerun <run-id> --failed`, recorded in the item's notes; a test failure is fixed on
     the branch.
   - Fallback merge: if `gh pr view <n> --json mergeStateStatus,autoMergeRequest` shows `CLEAN`
     with no `autoMergeRequest`, or an armed auto-merge has not merged within 10 minutes of the
     last green check, run `gh pr merge <n> --squash`. Never directly merge a PR that is not
     `CLEAN` or has pending checks.
   - Confirm `gh pr view <n> --json state` reports `MERGED` before any worktree or branch
     cleanup. Then:
   ```bash
   git checkout main
   git pull origin main
   git branch -D <branch-name>
   ```

6. Advance the item to terminal only if it is not already terminal (review-less items reached
   terminal in Step 4/5; a Direct-tier item with a review reached it in Step 5); otherwise skip:
   ```to-call
   {"tool":"advance","op":"transition","args":{"itemId":"4b7d0e19","trigger":"complete"}}
   ```
   On a run-wave item whose run is still open (under `review: "handoff"` it stays open through the review), this
   advance and the item's `orchestrator-confirmation` go under the coordinator token, then the run is closed with
   `post-run.md` Step 7, as in the Parallel tier below; with no open run both stay token-less.
   **Post-merge live acceptance** (an item that has children). If, after the merge, the only
   non-terminal children are live checks that need a refreshed plugin cache or a user opt-in, split
   them off exactly as run-wave `references/post-run.md` Step 7b "Post-merge live acceptance" says,
   (a) to (d), with the item in the feature's place and its parent as the holder's parent, BEFORE
   this advance: a terminal parent takes no new children.
7. **Retrospective.** The hook fires on a successful `runs_write` `close` (or the Stop backstop for
   a closed run without the `retro` mark), never on a terminal transition (mode
   `extensions.claude-code.retrospective.mode`). In `dispatch` mode follow its directive; in
   `nudge` mode or outside a run, run `/session-retrospective` by hand.
8. **Wave-boundary red-proofs** (for a single Direct/Delegated item, the "wave" is the item; run
   in the background, it gates neither the merge nor the terminal transition). Only the bug-fix
   revert ran before merge. The item's decision-point budget mutations (the planner's
   `red-proof-shape`; policy and skip categories in rule `test-author` section 5) run now, per run-wave
   `references/post-run.md` Step 7a items 1-6 with the item as the wave parent: one scratch
   worktree at the merged `main` SHA, each mutation applied with `references/patch-anchored.py`
   (`DRY=1` first) and run scoped to the test classes `test-manifest` maps to it (`-TestFiles`,
   `TO_GRADLE_OWNER=orchestrator`), one full suite only for a survivor, results in the item's
   `red-proof-results` note. Beyond Step 7a:
   - Mutations on node tests under `claude-plugins/` run in an extraction made by
     `node scripts/redproof-extract.mjs <sha> <outDir> <control test file>...` and count only after
     a green control there (exit 0; record its `control` line on the note). A test under the
     repo-root `scripts/tests/` reads no extracted path: mutate it in the scratch worktree itself,
     after an unmutated control run of the same file there (record pass/tests).
   - Classify every survivor on the note. A weak test is a real gap: name the missing assertion,
     carry it in to an open item that owns the file or create one test-only follow-up via
     `/task-orchestrator:create-item` (exempt from further proofs), re-run the mutation after it
     lands, and never reopen the item. An equivalent, unreachable or covered-at-another-seam (name
     the test) survivor needs no new test and is disposed by the feature review. An unclassified
     survivor blocks the feature review's red-proof line.
   Dispatch the runner with the Agent `description` `red-proofs:<item short>` (the Label rule of
   run-wave `post-run.md`; a one-item run's wave parent is the item). When the item had a run id,
   record its meters as `post-run.md` Step 7a item 7 says; with no run id, write no meters line
   and say so in the report.

Report the PR URL and a summary.

### Parallel tier — finalize ONCE at parent-feature completion

For Parallel-tier features with a shared feature worktree:

**For each child task** (after its review passes, if it has one):

1. For children whose schema/trait declares a review phase (e.g. `needs-task-review`),
   confirm `review-checklist` is filled. Children without one advance work→terminal
   directly — there is nothing to confirm.
2. Advance the child to terminal only if it is not already terminal (Step 4c item 7 already
   advanced each child out of work; a review-less child is terminal by now, so skip it). A child
   sitting in review takes one `advance` `transition` with `trigger: "complete"` (`itemId` the
   child's id) to reach terminal, and only after its review passes. While the run is open (under
   `review: "handoff"` it stays open through the review), that advance and the child's
   `orchestrator-confirmation` go under the coordinator token: a token-less write to an orchestrator-seat
   key of a run member is refused (`post-run.md` Step 3), and the coordinator token passes the seat
   gate (`current/docs/api-reference.md`, advance seat gate). Then close the run with `post-run.md` Step 7.
3. **Do NOT push. Do NOT create a PR.** The work is committed to `feat/<feature-slug>`
   inside the shared worktree; that's the integration point.

**When all children reach terminal**, the parent feature is ready to finalize (a child that is only a live
check needing a refreshed plugin cache or a user opt-in counts as done here; it is split off after the
merge, step 8):

1. Fill the parent's `implementation-notes` (cross-child content only, per the
   `feature-implementation` schema guidance: decisions between children, post-review merges, routed
   findings, deferred scope, or the one-line empty form) and `session-tracking` (the run record,
   per the `session-tracked` guidance; the children's notes are not re-listed) - under the coordinator token
   while the parent is a member of an open run; final content written once, in confirmed past tense;
   the only re-edits are the ones run-wave `references/post-run.md` Step 3 sanctions, and the step 8
   post-merge live acceptance line is written as that file's Step 7b (d) says).
2. **No second suite run.** The wave's one whole-suite run is Step 4c's, after the last writer
   commit (Step 4 "Verification"). If a writer seat committed after it (a fix seat, a merge round),
   that run was not the last writer's, so Step 4c runs again before the PR.
3. Advance the parent to review. The orchestrator never authors the parent's `review-checklist`,
   whatever the child count. With 2+ children, run the feature review of `run-wave`
   `post-run.md` Step 7b: one independent `task-orchestrator:reviewer` (explicit `model`, Agent `description`
   `reviewer:<parent-short>`; it runs under an assigned reviewer seat only when the parent is a member of the
   open run, otherwise token-less) over
   `git -C <feature-worktree-path> diff <base>...HEAD`, handed each child's verdict and
   disposition count lines (a child with no review phase is listed as such). The reviewer
   writes only feature-level findings, never re-lists a child's disposed observation, and fills
   the parent's `review-checklist`. The orchestrator records one line per finding in the
   parent's `orchestrator-confirmation` (`fixed <sha|PR> | follow-up <item-id> |
   accepted: <reason>`, ending `N of N disposed`). With exactly one child, Step 7b
   does not apply (it covers 2+ children only), but the rule is the same: the parent review is
   still done by an independent `task-orchestrator:reviewer` (explicit `model`) over the feature
   range, never by the orchestrator, handed the child's verdict and disposition count lines (or
   listed as review-less), and the dispositions go in the parent's `orchestrator-confirmation`.
   See Step 5's
   consolidated-reviewer paragraph, which never fills the parent note.
4. **CHANGELOG check and Docs line** - as the Direct/Delegated tier's step 2: the bullet the
   orchestrator or docs seat wrote before review (Step 4c), or `Changelog: none (<why>)` in the PR
   body; the Docs line and the `CHANGELOG.md` requirement are built from
   `git diff --name-only <base>...HEAD`, never from the plan, and recomputed after any merge round.
5. Push the feature branch:
   ```bash
   git -C <feature-worktree-path> push -u origin feat/<feature-slug>
   ```
6. Create **one** PR for the whole feature:
   ```bash
   gh pr create --base main --title "feat(<scope>): <feature description>" --body "$(cat <<'EOF'
   ## Summary
   <feature-level summary aggregating all children>

   ## Children completed
   - <child-1 title> (<MCP UUID>)
   - <child-2 title> (<MCP UUID>)
   ...

   ## Test Results
   <total test count, new tests added across feature>

   ## Review
   <feature reviewer verdict; N of N dispositions>

   ## Docs
   <every changed doc path from `git diff --name-only <base>...HEAD`>

   ## Changelog
   <the [Unreleased] bullet text, or none (<why>)>

   ## MCP
   Parent: <parent UUID>
   Children: <list of child UUIDs>
   EOF
   )"
   ```
7. **Merge gate** - run the Direct/Delegated tier's step 5 gate (green checks, fallback merge,
   state `MERGED` confirmed) first. Only after the PR is `MERGED`:
   ```bash
   git checkout main
   git pull origin main
   git worktree remove <feature-worktree-path>
   git branch -D feat/<feature-slug>
   ```
   Local `main` always tracks `origin/main` — no divergence, no `reset --hard` needed.
8. **Post-merge live acceptance.** If, after the merge, the only non-terminal children are live
   checks that need a refreshed plugin cache or a user opt-in, split them off exactly as run-wave
   `references/post-run.md` Step 7b "Post-merge live acceptance" says, (a) to (d), BEFORE
   completing the feature: a terminal parent takes no new children.
9. Advance the parent feature to terminal (only after `N of N disposed`) with `trigger: "complete"`;
   when step 8 applies this is its (e), run once after (a)-(d).
10. **Wave-boundary red-proofs.** Now that the feature PR has merged, run every child's
   decision-point budget mutations in ONE batch, as the Direct/Delegated tier's step 8 says, with
   the parent as the wave parent: results recorded once on the parent's `red-proof-results` note,
   the runner dispatched as `red-proofs:<parent-short>`, its meters recorded per `post-run.md`
   Step 7a item 7. Background; it gates nothing.
11. **Retrospective** - as the Direct/Delegated tier's step 7.

A hand-off note to a later wave or session (`wave<n>-handoff`, `phase<n>-handoff`) is the index:
merged PRs, what is unblocked, owed cells. File or line inventories go in a `docs_write` page
document that the note names by slug.

---

## Resuming In-Progress Work

Tier classification happens at Step 1 even when resuming. Classify the tier from
the item's tags, file scope, and note state, then resume using that tier's pipeline.

If an item is already past the queue phase (e.g., previously planned but not
implemented), the skill picks up from the current state:

| Current role | Resume from |
|-------------|-------------|
| queue (notes filled) | Step 3 — advance and proceed |
| queue (notes missing) | Step 3 — fill missing notes |
| work (in progress) | Step 4 — check implementation state |
| work (notes filled) | Step 4 — advance to review |
| review | Step 5 — run review |
| terminal | Already done — report status |
| an open run found (run-wave preflight F7) | `/task-orchestrator:run-wave --resume <runId>` |

Always call `items_read` `context` (`itemId`) first to determine exact state before
resuming.
