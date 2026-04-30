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

**Execution tier** — classify per the output style's Tier Classification table:

| Criteria | Tier |
|----------|------|
| 1-2 files, known fix, no migration/new API | **Direct** — orchestrator implements, tests, reviews inline |
| 3-10 files, single logical unit, clear or explorable scope | **Delegated** — single subagent, separate review agent |
| 11+ files, multiple independent work streams, dependency edges | **Parallel** — worktree agents, full pipeline |

Apply force-UP signals (migration, new API, multiple work streams, collaborative language) and force-DOWN signals (user says "just fix it", schema-free item) per the output style.

If the item has no schema tag, apply `quick-fix` for Direct tier or leave untagged for Delegated/Parallel (the `default` schema catches these).

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

**Capturing gradle's real exit code (use this pattern, not `2>&1 | tail -N`).**
Piping gradle into `tail` discards gradle's exit code — `tail` always exits 0
on a successful read of the log, so `BUILD FAILED` at the end of the gradle
output is reported by you as a successful run. Combined with gradle's daemon
incremental cache, this can hide broken compilation through entire CI cycles
(retro `568a8584`: 9 silently-failing tests shipped on PR #151 before the
followup audit caught it).

The reliable pattern, especially when running in `run_in_background`:

```bash
./gradlew :current:test 2>&1 > /tmp/gradle-out.log; EXIT=$?
echo "EXIT=$EXIT"
tail -30 /tmp/gradle-out.log
```

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

**Parallel dispatch into a shared feature worktree:**

```
Agent(
  prompt="""
  Working directory: <feature-worktree-path>
  Branch (already checked out): feat/<feature-slug>
  Scope (modify ONLY these files): <explicit list>

  Compile self-check (REQUIRED before returning):
    ./gradlew -p <feature-worktree-path> :current:compileKotlin 2>&1 > /tmp/agent-compile.log; EXIT=$?
  If EXIT != 0, fix the compile error before committing and returning.
  Do NOT run :current:test or :current:ktlintCheck — orchestrator owns full build
  verification. The compile self-check is fast (~3s) and catches type-mismatch /
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

This sweep is part of the orchestrator's verification step between waves, not the
implementing agent's responsibility — agents are file-scoped and can't see the full
fixture surface.

**Model selection — always set `model` explicitly on every Agent dispatch:**

| Agent purpose | Model |
|--------------|-------|
| Implementation, code changes, test writing | `model="sonnet"` |
| Architecture, complex multi-file synthesis | `model="opus"` |
| MCP bulk ops, materialization | `model="haiku"` |

Omitting `model` causes the agent to inherit the orchestrator's model (typically
opus), wasting tokens on sonnet-eligible implementation work.

**After implementation agents return:**

For Parallel-tier features, agents return having committed to `feat/<feature-slug>`
inside the shared feature worktree. Record each agent's commit SHA range alongside
the child's MCP item ID — needed for scoping the review agent later:

```
| Child UUID | Agent ID | Pre-SHA | Post-SHA | Changed Files |
|------------|----------|---------|----------|---------------|
| <uuid>     | <id>     | <sha>   | <sha>    | <file list>   |
```

Capture pre-commit SHA before dispatch (`git -C <feature-worktree> rev-parse HEAD`)
and post-commit SHA after the agent returns. The diff between them is exactly that
child's work:

```bash
git -C <feature-worktree> diff <pre-sha>..<post-sha> --name-only
```

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
2. **If `/simplify` made changes**, write or update tests to cover them. The simplify
   pass is still part of the work phase — all code changes require test coverage
   before advancing to review.
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
  lifecycle. Proceed to Step 6.
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
6. Fills the review-phase notes per `guidancePointer` with a verdict

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

Report the PR URL and a summary.

### Parallel tier — finalize ONCE at parent-feature completion

For Parallel-tier features with a shared feature worktree:

**For each child task** (after its review passes):

1. Confirm the child's `review-checklist` note is filled.
2. `advance_item(itemId=<child-uuid>, trigger="start")` to move work→review→terminal
   as the child's schema dictates.
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
3. **Build verification — orchestrator-owned, serialized.** After each parallel wave
   (or between sequential children), the orchestrator runs `:current:test` and
   `:current:ktlintCheck` from the feature worktree. Fix failures before advancing
   any child to review.
4. **Step 5 — Review per child, scoped to that child's commit range.** Review agent
   reads from the shared worktree but scopes its diff to `<pre-sha>..<post-sha>` for
   the child being reviewed.
5. **Step 6 — One PR at parent finalization.** Children advance to terminal without
   pushing or PR'ing. Only when the parent feature itself reaches terminal does the
   orchestrator push `feat/<slug>` and open the single feature-level PR.
6. **Track child commits** — maintain a table mapping child UUID → pre-commit SHA →
   post-commit SHA → status (implementing / reviewing / done / failed). Worktree
   path is shared across all children.
7. **Report at the end** — summarize children completed, review failures, and the
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
