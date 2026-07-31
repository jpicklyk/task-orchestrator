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
- Can process single items or multiple items in batch

---

## Step 1 — Assess the Work

Load the item(s) and determine the execution tier and interaction mode.

For each item, call `get_context(itemId=...)` to understand:
- Current role and gate status
- Schema tag (feature-implementation, bug-fix, etc.)
- Existing notes already filled
- Dependencies and blocked status

**Execution tier** — classify by this table (canonical source shared with the Workflow Orchestrator output style; edit the fragment, not this copy):

<!-- BEGIN GENERATED:tier-classification | source: claude-plugins/task-orchestrator/output-styles/_fragments/tier-classification.md · regen: node claude-plugins/task-orchestrator/output-styles/generate.mjs -->
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
- Schema tag is `default` or absent → eligible for Direct
<!-- END GENERATED:tier-classification -->

If the item has no schema tag, apply `quick-fix` for Direct tier or leave untagged for Delegated/Parallel (the `default` schema catches these).

**Trait application on classification.** When the tier resolves to Delegated or Parallel and the
workspace defines a `delegated` trait (it appears in `availableTraits` on create responses), apply
it before any dispatch — `traits: "delegated"` at item creation, or
`manage_items(operation="update", items=[{itemId: "<uuid>", traits: "delegated"}])` for an existing
item. This makes the orchestrator-filled `delegation-metadata` note schema-visible instead of
convention-only. Direct tier: do not apply it — nothing is delegated.

**Test-author trigger rule.** `bug-fix.default_traits` already includes `needs-test-author` — no
action needed, it applies automatically. For `feature-task` items, apply `needs-test-author` per
item (`manage_items(operation="update", items=[{itemId: "<uuid>", traits: "needs-test-author"}])`)
when acceptance criteria involve a predicate, algorithm, parser, validator, or state transition,
or when any force-ON signal is present: new public API surface, a database migration, a security
predicate, or a prior vacuous-test finding in this item's area. Other schema tags
(`feature-implementation`, `plugin-change`, `quick-fix`, and the global floor) do not carry the
trait. Direct tier: apply the trait only in its **temporal-only degraded mode**, and only on
bug-fixes — the `test-plan` gate and red-first rule still apply, `test-manifest` declares a single
actor, and there is no separate test-author dispatch. Other Direct-tier items are exempt
regardless of the criteria above — the tier is too small to separate authorship into a second
dispatch.

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
review cleaner.

---

## Step 2 — Prepare the Branch and Worktree

Sync local main before any implementation begins.

```bash
git checkout main
git pull origin main --tags
```

The branching/worktree strategy depends on tier:

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
FEATURE_SLUG=<short-feature-description>          # e.g. issue-117-followup
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

**Why one worktree per feature, not per child:** the feature is the natural PR boundary. Per-child PRs created cross-PR test contamination and PR-body staleness during the #117 follow-up (see retro `a7f6024f`). Shared worktree means one commit history, one CI cycle, one PR — and the parent feature's review-checklist gives a coherent point at which to finalize.

**Branch naming:**
- `feat/<feature-slug>` — feature-implementation parents (the integration branch)
- `fix/<short-description>` — bug-fix items (Direct or Delegated tier)
- `fix/<grouped-description>` — batch of related bug fixes (Delegated tier)
- `chore/<short-description>` — tech debt, refactoring (Direct tier)

---

## Step 3 — Queue Phase: Planning

This step is **tier-conditional**:

**Direct tier:** Skip this step entirely. No plan mode. No queue-phase notes. Call
`advance_item(trigger="start")` immediately to move queue→work. The `quick-fix`
schema has no queue-phase required notes, so the gate passes.

**Delegated tier:** Fill queue-phase notes per schema. Use `get_context(itemId=...)`
to see `expectedNotes` and `guidancePointer`. Pre-plan-workflow is optional — use
only if scope needs exploration. Post-plan-workflow only if child items need
materialization. Advance: `advance_item(trigger="start")`.

**Parallel tier:** Full planning pipeline:

**Collaborative mode:**
1. Tell the user the item is ready for planning and ask them to enter plan mode.
   The `pre-plan-workflow` and `post-plan-workflow` hooks fire automatically on
   plan mode entry and exit, handling context gathering and materialization.
2. During planning, follow the `guidancePointer` for each required note — this
   will reference the spec-quality skill where applicable.
3. After plan approval and post-plan materialization, advance the item:
   `advance_item(trigger="start")`

**Autonomous mode:**
1. Read and follow the `pre-plan-workflow` skill — gather existing MCP state,
   check schema requirements, and understand the definition floor.
2. Research the codebase — explore relevant files, understand current state.
3. Fill all queue-phase notes following the `guidancePointer` for each. The
   spec-quality framework applies regardless of mode.
4. Read and follow the `post-plan-workflow` skill — materialize child items
   if the plan calls for them.
5. Advance the item: `advance_item(trigger="start")`

The gate will reject advancement if required notes are missing. If rejected, fill
the missing notes and retry.

**Do not confuse this with resource-lease contention.** A queue→work `advance_item` can also
fail with `applied: false`, `errorCode: "resource_unavailable"`, `errorKind: "transient"` — a
resource a trait on this item declares (`resources:`) is currently held by another item. This
is not a note gate failure: filling notes will not fix it. Work a different item and retry
later (`retryAfterMs` is a hint), or report the contended key(s) to the user.

---

## Step 4 — Work Phase: Implementation

**Verification commands.** Throughout this step, "run tests" means running BOTH
the test suite AND the project linter:

```bash
./gradlew :current:test
./gradlew :current:ktlintCheck
```

CI enforces both — a green test run with failing lint will still block the PR.
If `ktlintCheck` fails, run `./gradlew :current:ktlintFormat` to auto-fix
formatting violations, then verify with `ktlintCheck` again and re-run tests.
Include both commands in every implementation-agent and review-agent prompt.

**Who runs the lint cycle** (proposal `ee6f5d32`, ~9 sessions of evidence):
- **Delegated tier** (agent owns gradle): the implementation agent runs the
  `ktlintCheck` → `ktlintFormat` → re-verify cycle itself before committing.
  Validated 2026-07-13 (PRs #213/#214/#215): zero orchestrator fix-up commits.
- **Parallel tier** (orchestrator owns gradle): agents include `:current:ktlintFormat`
  in their compile self-check (see the dispatch template below); the orchestrator
  additionally runs `ktlintFormat` before `ktlintCheck` after each commit batch —
  historically 3-5 fix-up commits per multi-phase run when skipped.

**Capturing gradle's real exit code (use this pattern, not `2>&1 | tail -N`).**
Piping gradle into `tail` discards gradle's exit code — `tail` always exits 0
on a successful read of the log, so `BUILD FAILED` at the end of the gradle
output is reported by you as a successful run. Combined with gradle's daemon
incremental cache, this can hide broken compilation through entire CI cycles
(retro `568a8584`: 9 silently-failing tests shipped on PR #151 before the
followup audit caught it).

The reliable pattern, especially when running in `run_in_background`:

```bash
./gradlew :current:test > /tmp/gradle-out.log 2>&1; EXIT=$?
echo "EXIT=$EXIT"
tail -30 /tmp/gradle-out.log
```

**Redirect ordering matters:** use `> /tmp/log 2>&1`, NOT `2>&1 > /tmp/log` — the
latter is evaluated left-to-right and leaks stderr (where gradle writes compile
errors) to the terminal instead of the log (retro `ac25db89`: a compileTestKotlin
failure was invisible in the captured log until the ordering was fixed).

`EXIT=$?` captures gradle's actual exit code before any pipe consumes it.
Read the captured exit code AND the tail of the log; never trust the tail
alone. This applies to every orchestrator-owned gradle invocation throughout
this step.

**Use `--rerun-tasks` after dependency upgrades or large refactors.** Gradle's
incremental compile cache retains class files from prior good builds. After a
`gradle/libs.versions.toml` bump or a refactor that changes public API surfaces
(removed methods, renamed types, sealed-class arms, generic-parameter shifts),
incremental compilation can keep the OLD class files alongside source that no
longer compiles, producing apparent BUILD SUCCESSFUL on stale bytecode. Run
`./gradlew :current:test --rerun-tasks` once after such changes to force a
clean run; ordinary incremental builds are safe afterwards.

This step is **tier-conditional**:

**Direct tier:** Implement directly. Edit the files, run the test suite. No subagent
dispatch. No `/simplify` pass. Fill the `session-tracking` note (required by both
`quick-fix` and `default` schemas) with a brief summary of what changed and test
results. Advance to review:
`advance_item(trigger="start")`.

**Delegated and Parallel tiers:** Use `get_context(itemId=...)` to see work-phase
`expectedNotes` and `guidancePointer` values. Fill each required note following its
guidance. Follow the delegation model from your output style (model selection, return
formats, UUID inclusion). The key decisions at this step are:

- **Single item (Delegated):** delegate to one implementation subagent or implement
  directly. Subagent works in the main directory on the working branch.
- **Multiple child tasks, independent (Parallel):** dispatch parallel subagents into the
  **shared feature worktree** created in Step 2. Each agent receives the worktree path
  and branch name. Do **not** use `isolation: "worktree"` on the Agent tool — that would
  spawn a separate worktree per dispatch, which is the deprecated per-child PR pattern.
- **Multiple child tasks, dependent:** dispatch sequentially into the shared feature
  worktree. Wait for each agent's commit to land before dispatching the next.

**Test-file ownership boundary.** Every implementation dispatch (Delegated single agent or
Parallel per-child agent) excludes `src/test/**` from scope — implementers do not create or
modify test files. When a change surfaces a needed test update, the agent reports it in its
return (or in `implementation-notes`) rather than editing the test itself; the test author
(Step 4b) owns that file tree exclusively on items carrying `needs-test-author`.

**Parallel dispatch into a shared feature worktree:**

```
Agent(
  prompt="""
  Working directory: <feature-worktree-path>
  Branch (already checked out): feat/<feature-slug>
  Scope (modify ONLY these files): <explicit list>
  Do NOT create or modify any file under src/test/** — test authoring is a separate,
  independent dispatch (Step 4b) on items carrying needs-test-author. If your change
  surfaces a needed test update, report it in your return; never edit the test yourself.

  Format + compile self-check (REQUIRED before returning):
    ./gradlew -p <feature-worktree-path> :current:ktlintFormat :current:compileKotlin :current:compileTestKotlin > /tmp/agent-compile.log 2>&1; EXIT=$?
  If EXIT != 0, fix the reported error — a compile error OR a lint violation
  ktlintFormat could not auto-correct (e.g. line >140 chars, colons in backticked
  test names) — before committing and returning.
  Do NOT run :current:test or :current:ktlintCheck — orchestrator owns full build
  verification. ktlintFormat is formatting-only and idempotent; it prevents the
  recurring lint fix-up commits (proposal ee6f5d32). The compile self-check is
  fast (~3s) and catches type-mismatch /
  signature errors that gradle's incremental cache may otherwise mask in the
  orchestrator's later test run (retro `568a8584`: H3's `dbNow()` shipped with a
  Result<T> vs Instant return type mismatch that was hidden for ~6 hours).

  After self-check passes, commit your changes with a descriptive message.
  """,
  model="sonnet",
  subagent_type="general-purpose"
  // NOTE: no isolation parameter — agents share the feature worktree
)
```

**File-edit overlap discipline:** Parallel agents in a shared worktree must operate on
non-overlapping files. The orchestrator scopes each agent's prompt to a specific file list
(per MEMORY.md §"Parallel File-Edit Delegation"). When inherent overlap exists, dispatch
sequentially.

**Contract-change sweep discipline.** When a child task tightens a contract — making a
parameter required, adding `validate()` invariants, narrowing a sealed-class arm, or
otherwise rejecting inputs that earlier passed — the orchestrator must sweep the rest
of the codebase before advancing to review. Two recurrences (retros `a7f6024f` and
`568a8584`) showed that:

- Pre-existing test fixtures constructed under the old contract will fail under the
  new one. Example: H2's `WorkItem.validate()` claim-field invariants broke 8+ test
  fixtures that constructed mixed-state items via separate `Instant.now()` calls
  (microsecond drift) or partial claim fields.
- The failure typically surfaces on a *different* PR's merge commit, not the PR that
  introduced the contract change — the original PR's tests passed because they used
  the new contract correctly.

For each contract-tightening change in this run:
1. Identify the affected tool / class / method.
2. Grep all test files (and other call sites) for usages: `grep -rn "<tool>\|<class>\|<method>"`.
3. Verify every usage is consistent with the new contract. Update any that are not.
4. Re-run the full `:current:test` suite (orchestrator-owned, not the agent) to
   confirm no fixture-vs-contract conflicts surfaced elsewhere.
5. **Fixture repairs are orchestrator-owned and construction-only.** When a fixture fails under
   the tightened contract, the orchestrator may adjust how the fixture is *constructed* (fix the
   stale call site) but must never adjust what it *asserts*. Anything that would touch an
   expectation instead of a construction call is not a fixture repair — re-dispatch the test
   author (this preserves the independence the trait exists to protect).
6. **Doc-claims sweep after behavior-changing fixes:** when an orchestrator-owned
   bug-fix changes shipped behavior after documentation was authored (e.g. a
   tokenizer or default flips mid-run), grep all changed docs for claims about
   the OLD behavior before finalizing (retro `ac25db89`: "case-insensitive"
   survived in 3 places after the fix made search case-sensitive).

This sweep is part of the orchestrator's verification step between waves, not the
implementing agent's responsibility — agents are file-scoped and can't see the full
fixture surface.

**Model selection — always set `model` explicitly on every Agent dispatch:**

| Agent purpose | Model |
|--------------|-------|
| Implementation (production code only) | `model="sonnet"` |
| Independent test authoring (Step 4b) | `model="sonnet"` |
| Architecture, complex multi-file synthesis | `model="opus"` |
| MCP bulk ops, materialization | `model="haiku"` |

Omitting `model` causes the agent to inherit the orchestrator's model (typically
opus), wasting tokens on sonnet-eligible implementation work.

**After implementation agents return:**

For Parallel-tier features, agents return having committed to `feat/<feature-slug>`
inside the shared feature worktree. Record each agent's commit SHA range alongside
the child's MCP item ID — needed for scoping the review agent later:

```
| Child UUID | Agent ID | Pre-SHA | Post-SHA | Test-Pre-SHA | Test-Post-SHA | Changed Files |
|------------|----------|---------|----------|--------------|---------------|---------------|
| <uuid>     | <id>     | <sha>   | <sha>    | <sha>        | <sha>         | <file list>   |
```

Capture pre-commit SHA before dispatch (`git -C <feature-worktree> rev-parse HEAD`)
and post-commit SHA after the agent returns. The diff between them is exactly that
child's work:

```bash
git -C <feature-worktree> diff <pre-sha>..<post-sha> --name-only
```

`Test-Pre-SHA`/`Test-Post-SHA` are captured the same way around the test author's dispatch
(Step 4b) and stay blank until that wave runs.

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
`git checkout` of the crossed-boundary file back to the prior SHA) and recorded in
`implementation-notes` — do not silently keep a cross-ownership commit.

---

## Step 4b — Test Authoring

Applies only to items carrying the `needs-test-author` trait. Runs after implementation agents
return (their commits exist on the branch/worktree) and before the orchestrator's build
verification below — the test author compiles against the real implemented surface, not a
predicted one.

**Per-tier sequencing:**

- **Direct tier:** temporal-only degraded mode only (see Step 1) — no separate agent. The
  orchestrator itself writes the tests in a distinct pass after implementation, `test-manifest`
  declares the single actor, and the disjointness check above does not apply (one actor, one
  range).
- **Delegated tier:** one additional **sequential** dispatch on the same branch, after the
  implementation agent's commit lands. The test author owns its own gradle cycle on that branch
  (`ktlintCheck` → `ktlintFormat` → re-verify, same rule as Step 4's "Who runs the lint cycle")
  and runs `:current:test` itself before committing.
- **Parallel tier:** one test-author agent **per child**, dispatched as a dedicated wave between
  the implementation wave and the orchestrator's build verification (below). Authors in this
  tier do NOT run `:current:test` or `:current:ktlintCheck` — only `ktlintFormat` plus a compile
  self-check, mirroring the implementation dispatch template, since the orchestrator owns full
  build verification for the wave.

**Blindness rule.** The test author may read: the item's `test-plan` note, public signatures,
domain models, existing test conventions/docs, and the implementer's changed-file **names** (not
content). The test author must NOT read: the implementation diff content, the implementer's own
tests, `implementation-notes`, or `session-tracking`. This is what makes the separation real
rather than nominal — tests probe the spec, not the implementation's behavior.

**Oracle-provenance obligation.** Every scenario's expected result must trace to a source
declared in `test-plan` (spec clause, stated algorithm, external reference) — never "what the
code returns" and never the ticket's own worked example.

**Manifest duty.** Before returning, the test author fills `test-manifest` (work, required):
actor id, test file paths, commit SHA range, S-id→test coverage mapping (covered /
not-covered:reason), probes executed with results, forbidden-pattern declaration (every
`assumeTrue`/escape with justification, or none), and any implementer-modification-to-test-files
rationale (should be none if ownership held).

**Skill routing.** Invoke the `test-author` skill before filling `test-plan` or `test-manifest`
— it carries the scenario-derivation, oracle-derivation, blindness, and forbidden-pattern
framework this step depends on.

**Test-author dispatch template (Delegated or Parallel):**

```
Agent(
  prompt="""
  Working directory: <worktree-or-branch-path>
  Branch (already checked out): <branch-name>
  Scope (modify ONLY): src/test/** for this item's changed surface. Do NOT create or
  modify any file under src/main/**.

  You are the TEST AUTHOR for this item, independent of implementation. Read ONLY:
  the item's test-plan note, public signatures, domain models, existing test
  conventions, and the implementer's changed FILE NAMES (not diff content). Do NOT
  read: the implementation diff, the implementer's tests, implementation-notes, or
  session-tracking.

  Invoke the test-author skill before filling test-plan/test-manifest content — it
  defines scenario derivation, oracle derivation, blindness, and forbidden patterns.

  Every scenario's expected result must trace to a stated oracle (spec clause,
  algorithm, external reference) — never "what the code returns."

  [Parallel tier only] Format + compile self-check (REQUIRED before returning):
    ./gradlew -p <worktree-path> :current:ktlintFormat :current:compileKotlin :current:compileTestKotlin
  Do NOT run :current:test or :current:ktlintCheck — orchestrator owns full build
  verification for this wave.
  [Delegated tier only] Run the full lint cycle yourself (ktlintCheck -> ktlintFormat
  -> re-verify) and :current:test before committing — you own gradle on this branch.

  Fill test-manifest (actor id, file paths, SHA range, S-id-to-test mapping, probes,
  forbidden-pattern declaration) before returning. Commit your changes with a
  descriptive message.
  """,
  model="sonnet",
  subagent_type="general-purpose"
)
```

Capture the test author's pre/post commit SHAs the same way as implementation agents
(`Test-Pre-SHA` / `Test-Post-SHA` in the tracking table above), then run the disjointness check.

---

**Build verification (orchestrator-owned, serialized).** After each parallel-batch
completes (or between sequential children), run from the feature worktree:

```bash
git -C <feature-worktree> status                            # confirm clean tree
./gradlew -p <feature-worktree> :current:test
./gradlew -p <feature-worktree> :current:ktlintCheck
```

A failure means a recently-committed child broke something. Dispatch a fix agent
(same shared worktree) before continuing. Do **not** advance any child to review
until the build is green — the trend memory has multiple sessions of
`flaky-test-hides-real-bug` showing why retry-until-green is wrong.

**Why orchestrator owns gradle invocations:** `./gradlew` runs against a single
Gradle daemon and a single `build/` cache per project directory. Parallel
`gradlew test` invocations against the shared feature worktree will queue at the
daemon, corrupt the build cache, or hit Windows file locks. Serializing build
verification at the orchestrator prevents this without slowing the agents (they're
not running gradle).

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
4. Fill all work-phase notes following their `guidancePointer` — focus on context
   that downstream agents need to know
5. After implementation completes:
   - **Subagent delegation:** The agent returns after filling work-phase notes.
     The orchestrator then calls `advance_item(trigger="start")` to advance
     the item to the next phase.
   - **Direct implementation:** The orchestrator calls `advance_item(trigger="start")`
     itself after filling work-phase notes.
   In both cases, inspect `newRole` in the response to determine what comes next
   (see Step 5).

---

## Step 5 — Review Phase

Before dispatching or performing review, check the item's current role. Inspect
`newRole` from the `advance_item` response in the previous step:

- **If `newRole` is `terminal`:** The item's schema has no review phase (lightweight
  lifecycle). Review dispatch is not needed — the item completed through its natural
  lifecycle. Proceed to Step 6. Note: `feature-task` items skip review by default
  (work→terminal directly) — the `needs-task-review` trait re-enables a review phase
  for a specific child when needed.
- **If `newRole` is `review`:** Continue with review per tier below.

This step is **tier-conditional**:

**Direct tier:** Perform an inline review. Read the diff, verify correctness, confirm
tests pass. If the review-phase note has a `skillPointer` (visible in the `advance_item`
response or via `get_context`), invoke that skill for the evaluation framework before
filling the review note. Write the review note, then advance to terminal:
`advance_item(trigger="start")`. No separate review agent —
the overhead exceeds the risk for 1-2 file changes with known fixes.

**Delegated and Parallel tiers:** Dispatch a **separate** review agent. The agent
that implemented the code must not review its own work.

**If the implementation used the shared feature worktree** (Parallel tier), the
review agent operates in that worktree, scoped to **just this child's commits**.
The diff range is `<pre-sha>..<post-sha>` captured during dispatch (see Step 4).

**Review agent template (copy verbatim, fill placeholders):**

```
You are reviewing one child task within a shared feature worktree.

- Feature worktree: <FEATURE_WORKTREE_PATH>
- Feature branch: <FEATURE_BRANCH>
- This child's commit range: <PRE_SHA>..<POST_SHA>
- This child's changed files:
  <OUTPUT OF git -C <FEATURE_WORKTREE_PATH> diff <PRE_SHA>..<POST_SHA> --name-only>
- Other children may have committed before/after this one. Do NOT review their work
  — your scope is the diff range above only.

Run ALL commands from within the feature worktree.
Read ALL files from that directory. Do NOT read from the main working directory.

Tests have already been verified green by the orchestrator after the most recent
commit batch. Do NOT re-run gradle — focus on plan alignment, test quality, and
simplification per the review-quality skill.

Report every finding at every severity — do not self-filter to a high-severity-only
bar. Mark each finding blocking or observation and state your confidence; the
orchestrator's verdict handling is the downstream filter, not your own judgment.
```

**If using Direct or Delegated tier** (single working branch on the main directory),
the review agent reads from the working branch and runs tests itself per the
existing template — no worktree-specific scoping needed.

The review agent:
1. Reads the review-quality skill
2. Uses `get_context(itemId=...)` to load the item's notes and review-phase requirements
3. Reads the changed files (from the worktree path if isolated, or the working branch)
4. Runs the test suite AND the linter (both commands from Step 4's "Verification commands") — from the worktree if isolated. A PR with failing lint will not merge.
5. Evaluates plan alignment, test quality, and simplification
6. **On items carrying `needs-test-author`, additionally:**
   - **Two-range ownership check:** confirm the impl-range/author-range disjointness recorded
     during Step 4b actually holds by spot-checking `git diff <impl-range> --name-only` touches
     no `src/test/**` path and `git diff <test-range> --name-only` touches no `src/main/**` path.
   - **Arbitration-record check:** if any red author-test required arbitration (implementation
     wrong / test wrong / spec wrong), confirm the outcome is recorded in `implementation-notes`
     with the required detail (spec citation for a "test wrong" call: note key + quoted
     criterion + asserted-vs-observed).
   - **`assumeTrue`/`@Disabled` scan on author-owned files:** grep the test author's changed
     files for `assumeTrue`, `@Disabled`, or other weakening introduced *after* the author's
     first commit in this range. Any such introduction is an automatic blocking finding —
     independence does not permit softening a red test to unblock a wave.
   Verdict rule: `test-independence-audit` fails to `not-independent` (blocking) if any of the
   three checks above fails, regardless of how green the test suite is.
7. Fills the review-phase notes per `guidancePointer` with a verdict

**Handling the verdict:**

| Verdict | Action |
|---------|--------|
| **Pass** | Proceed to Step 6 |
| **Pass with observations** | Proceed to Step 6; log observations for follow-up |
| **Fail — blocking issues** | Stop and report to the user with the full findings. Do not attempt to fix autonomously — bring the human into the loop. |

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
   Step 4b disjointness check — do not let it ride into the squash-merge unreconciled.

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

2. Push the working branch:
   ```bash
   git push -u origin <branch-name>
   ```

3. Create the PR:
   ```bash
   gh pr create --base main --title "<type>(<scope>): <description>" --body "$(cat <<'EOF'
   ## Summary
   <2-4 bullets>

   ## Test Results
   <test count, pass/fail, new tests>

   ## Review
   <verdict summary>

   ## MCP
   <item ID>
   EOF
   )"
   ```

4. After PR merges:
   ```bash
   git checkout main
   git pull origin main
   git branch -D <branch-name>
   ```

5. Advance the item to terminal:
   ```bash
   advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
   ```
6. After the item reaches terminal, follow the retrospective hook's directive if one fires (see `retrospective.mode`).

Report the PR URL and a summary.

### Parallel tier — finalize ONCE at parent-feature completion

For Parallel-tier features with a shared feature worktree:

**For each child task** (after its review passes, if it has one):

1. For children whose schema/trait declares a review phase (e.g. `needs-task-review`),
   confirm `review-checklist` is filled. Children without one advance work→terminal
   directly — there is nothing to confirm.
2. `advance_item(itemId=<child-uuid>, trigger="start")` to move work→review→terminal
   (or work→terminal directly) as the child's schema dictates.
3. **Do NOT push. Do NOT create a PR.** The work is committed to `feat/<feature-slug>`
   inside the shared worktree; that's the integration point.

**When all children reach terminal**, the parent feature is ready to finalize:

1. Fill the parent's `implementation-notes` and `session-tracking` notes (aggregating
   across children — distributed-tracking pattern works as today).
2. Run final verification from the feature worktree:
   ```bash
   ./gradlew -p <feature-worktree-path> :current:test
   ./gradlew -p <feature-worktree-path> :current:ktlintCheck
   ```
3. Advance the parent to review and fill `review-checklist` (orchestrator-authored,
   summarizing across all children's reviews).
4. Push the feature branch:
   ```bash
   git -C <feature-worktree-path> push -u origin feat/<feature-slug>
   ```
5. Create **one** PR for the whole feature:
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
   <feature-level review verdict, references each child's review-checklist>

   ## MCP
   Parent: <parent UUID>
   Children: <list of child UUIDs>
   EOF
   )"
   ```
6. After PR merges:
   ```bash
   git checkout main
   git pull origin main
   git worktree remove <feature-worktree-path>
   git branch -D feat/<feature-slug>
   ```
7. Advance the parent feature to terminal.
8. Retrospective — the plugin's retrospective hook fires on the parent's terminal transition.
   In `dispatch` mode (see `retrospective.mode` in `.taskorchestrator/config.yaml`) it directs
   a background `/session-retrospective` automatically — follow its directive; in `nudge` mode,
   or if no directive arrives, suggest running it manually.

### Why one PR at parent finalization, not per child

- **Coherent review context.** The PR diff shows the whole feature, not N disjoint pieces.
- **One CI cycle per feature** instead of N. Local verification (orchestrator-owned
  gradle runs between commits) gives equivalent regression signal during development.
- **No cross-PR contamination.** Contract changes can't surface on a sibling's merge
  commit because there are no sibling PRs.
- **No PR-body staleness.** The PR body is authored once, after the feature is done,
  describing what actually shipped.
- **Aggregate retrospective material.** Distributed `session-tracking` notes across
  children plus parent-level aggregation gives clean retro input (validated by retro
  `a7f6024f`).

Local `main` always tracks `origin/main` — no divergence, no `reset --hard` needed.

---

## Autonomous Batch Processing

When processing a Parallel-tier feature with multiple child tasks autonomously:

1. **Step 2 — One worktree, one branch.** Orchestrator creates the feature worktree
   and feature branch (`feat/<slug>`) at planning time. All children share it.
2. **Step 4 — Dispatch into shared worktree.** Agents dispatched without
   `isolation: "worktree"`. Independent children dispatch in parallel waves; dependent
   children dispatch sequentially. Orchestrator scopes each agent's file list to
   prevent overlap.
3. **Step 4b — Test-author wave.** For children carrying `needs-test-author`, dispatch one
   test-author agent per child as its own wave, after that child's implementation wave and
   before build verification. Authors run `ktlintFormat` + compile self-check only (never
   `:current:test`/`:current:ktlintCheck` — the orchestrator owns those next). Capture
   Test-Pre-SHA/Test-Post-SHA per child and run the disjointness check before proceeding.
4. **Build verification — orchestrator-owned, serialized.** After each parallel wave
   (implementation or test-author), the orchestrator runs `:current:test` and
   `:current:ktlintCheck` from the feature worktree. Fix failures before advancing
   any child to review.
5. **Step 5 — Review per child, scoped to that child's commit range, when the child has
   a review phase.** `feature-task` children skip review by default (work→terminal
   directly) unless the `needs-task-review` trait is set. When a review phase applies,
   the review agent reads from the shared worktree but scopes its diff to
   `<pre-sha>..<post-sha>` for the child being reviewed, plus `<test-pre-sha>..<test-post-sha>`
   when a test-author wave ran for that child.
6. **Step 6 — One PR at parent finalization.** Children advance to terminal without
   pushing or PR'ing. Only when the parent feature itself reaches terminal does the
   orchestrator push `feat/<slug>` and open the single feature-level PR.
7. **Track child commits** — maintain a table mapping child UUID → pre-commit SHA →
   post-commit SHA → Test-Pre-SHA → Test-Post-SHA → status (implementing / test-authoring /
   reviewing / done / failed). Worktree path is shared across all children.
8. **Report at the end** — summarize children completed, review failures, and the
   single PR URL.

If any child hits a review failure, continue processing siblings (their commits are
already in the feature branch). Report all failures together at the end. The orchestrator
decides whether the failed child blocks parent finalization (e.g. fix-and-re-review),
can be cancelled (descope from the feature), or warrants reverting its commits.

**Bug-fix batches** (multiple unrelated fixes) — these are NOT a Parallel-tier feature.
Use Delegated tier per item, each with its own branch and PR (the legacy per-item flow).
The shared-worktree pattern applies only when items share a parent feature item.

---

## Worktree Strategy

For full setup, dispatch patterns, lifecycle, parallel validation, and test baseline
management, see [WORKTREE.md](WORKTREE.md).

**Quick reference:**

| Tier | Worktree | Branch | PR scope |
|------|----------|--------|----------|
| Direct / Delegated (single item) | None — work on main directory | `<type>/<slug>` | One PR per item |
| Parallel (parent feature with N children) | One **shared feature worktree** | `feat/<feature-slug>` (one branch for all children) | One PR at parent finalization |

**Do NOT use `isolation: "worktree"` on the Agent tool for Parallel-tier child dispatches.**
That spawns a separate worktree per dispatch — the deprecated per-child PR pattern.
For Parallel tier, the orchestrator pre-creates one shared worktree in Step 2 and
dispatches each child agent into it.

**When NOT to create any worktree:**
- Direct/Delegated tier (single item — work on the main directory)
- Pure MCP operations with no file modifications
- Orchestrator implementing directly

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

Always call `get_context(itemId=...)` first to determine exact state before
resuming.
