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

## Step 2 — Prepare the Branch

Sync local main before any implementation begins.

```bash
git checkout main
git pull origin main --tags
```

**If NOT using worktree isolation** (orchestrator implements directly or dispatches
a single non-isolated agent), create a working branch:

```bash
git checkout -b <branch-name>
```

This branch will be pushed to origin and merged via GitHub PR after review.
See Step 6 for the PR workflow.

**If using worktree isolation**, skip branch creation — each worktree agent gets
its own isolated branch automatically. See Worktree Isolation below. Worktree
branches are pushed and merged via PR after review.

**Branch naming:**
- `feat/<short-description>` — feature-implementation / feature-task items
- `fix/<short-description>` — bug-fix items
- `fix/<grouped-description>` — batch of related bug fixes
- `chore/<short-description>` — tech debt, refactoring, observations

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
  directly.
- **Multiple child tasks, independent (Parallel):** dispatch parallel subagents with
  worktree isolation (see Worktree Isolation below).
- **Multiple child tasks, dependent:** dispatch sequentially — wait for each agent
  to return before dispatching the next.

**Model selection — always set `model` explicitly on every Agent dispatch:**

| Agent purpose | Model |
|--------------|-------|
| Implementation, code changes, test writing | `model="sonnet"` |
| Architecture, complex multi-file synthesis | `model="opus"` |
| MCP bulk ops, materialization | `model="haiku"` |

Omitting `model` causes the agent to inherit the orchestrator's model (typically
opus), wasting tokens on sonnet-eligible implementation work.

**After implementation agents return:**

If agents used worktree isolation, the Agent tool result includes the **worktree
path** and **branch name**. Capture both — they are needed for review and PR
creation. Record them alongside the MCP item ID:

```
| Item UUID | Worktree Path | Branch | Changed Files |
|-----------|---------------|--------|---------------|
| <uuid>    | <path>        | <branch> | <file list> |
```

To get the changed files list for a worktree, run:
```bash
git -C <worktree-path> diff main --name-only
```

**Post-implementation steps** (run in the worktree if isolated, or on the working
branch if not):

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

**If the implementation used worktree isolation**, the review agent must operate
in the same worktree so it reads the correct files and runs tests against the
actual changes. Include in the review agent prompt:

- The **worktree path** (from the implementation agent's return)
- The **branch name** in the worktree
- The **changed files list** (from `git -C <worktree-path> diff main --name-only`)
- Instruction: "Run all commands and read all files from within the worktree at
  `<worktree-path>`. Do NOT read files from the main working directory."

**Review agent worktree template (copy verbatim, fill placeholders):**

```
You are reviewing implementation work in an isolated worktree.
- Worktree path: <WORKTREE_PATH>
- Branch: <BRANCH_NAME>
- Changed files: <OUTPUT OF git -C <WORKTREE_PATH> diff main --name-only>

Run ALL commands from within the worktree at <WORKTREE_PATH>.
Read ALL files from that directory. Do NOT read from the main working directory.
```

**If NOT using worktree isolation**, the review agent reads from the current
working branch as normal.

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

## Step 6 — Commit and Push PR

After review passes, commit the changes, push the branch, and create a GitHub PR.
Each feature or logical unit of work gets its own PR — no batching on local `main`.

### Commit on the working branch

**If using worktree isolation**, commit from the worktree:
```bash
git -C <worktree-path> add <specific-files>
git -C <worktree-path> commit -m "..."
```

**If NOT using worktree isolation**, commit on the working branch as normal.

Stage only the files related to the implementation. Do not stage unrelated changes
that happen to be in the working tree.

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

### Push and create PR

Push the branch to origin and create a PR:

**If using worktree isolation**, push the worktree branch:
```bash
git -C <worktree-path> push -u origin <worktree-branch>
```

**If NOT using worktree isolation**, push the working branch:
```bash
git push -u origin <branch-name>
```

Create the PR:
```bash
gh pr create \
  --base main \
  --title "<type>(<scope>): <short description>" \
  --body "$(cat <<'EOF'
## Summary

<2-4 bullets covering what changed and why>

## Test Results

<test count, pass/fail, new tests added>

## Review

<review verdict summary — plan alignment confirmed, test quality verified>

## MCP Items

<list of MCP item IDs addressed by this PR>
EOF
)"
```

### After the PR is merged

Sync local main and clean up:
```bash
git checkout main
git pull origin main
git branch -D <branch-name>
```

Local `main` always tracks `origin/main` — no divergence, no `reset --hard` needed.

### Advance to terminal

After the PR is created:
```bash
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```

Report the PR URL and a summary of what was done.

---

## Autonomous Batch Processing

When processing multiple items autonomously:

1. **Group assessment** — evaluate all items, identify which can be grouped and which
   need separate branches
2. **Parallel execution** — use worktree isolation for independent work streams.
   Sequential execution for items with dependency edges between them.
3. **Per-item pipeline** — each worktree goes through Steps 4-6 independently:
   implementation → capture worktree metadata → simplify → review (in worktree) → push and PR
4. **Track all worktrees** — maintain a table mapping item UUID → worktree path →
   branch → status (implementing / reviewing / PR created / failed)
5. **PR each** — after review passes, push each worktree branch to origin and create
   a GitHub PR. Run the full test suite before pushing to catch integration issues.
6. **Report at the end** — summarize items completed, any review failures, and PR URLs

If any item in the batch hits a review failure, continue processing other items
and report all failures together at the end.

---

## Worktree Isolation

Use `isolation: "worktree"` on the Agent tool to give each agent an isolated copy
of the repository. This prevents file conflicts, cross-cutting changes, test
baseline contamination, and — critically — ensures changes are committed to a
real branch that survives the agent's lifecycle.

**When to use worktrees:**
- Parallel dispatch of multiple implementation agents (prevents file conflicts)
- Any implementation dispatch where you need the changes on an isolated branch
- When you want the implementation agent to commit independently

**When NOT to use worktrees:**
- Tasks that depend on each other's file changes (use sequential dispatch instead)
- Pure MCP operations with no file modifications (e.g., materialization subagents)
- Orchestrator implementing directly (already on a working branch)

### Dispatch pattern

```
Agent(
  prompt="...",
  model="sonnet",
  isolation="worktree",
  subagent_type="general-purpose"
)
```

**Scoping rules:** The `subagent-start` hook automatically injects commit, scope,
and cd-discipline rules into every subagent. You do NOT need to include these in
delegation prompts. Focus prompts on task-specific context:
- Item UUID and what to implement
- Which files to modify (explicit list when possible)
- Test expectations
- Return format

Cross-cutting changes (version bumps, shared config) are handled by the
orchestrator after all agents return.

### Worktree lifecycle

Review happens in the worktree. After review passes, push the worktree branch
to origin and create a GitHub PR.

```
1. Orchestrator dispatches agent with isolation: "worktree"
2. Agent works in isolated worktree, commits to worktree branch
3. Agent returns → result includes worktree path and branch name
4. Orchestrator captures worktree metadata (path, branch, changed files)
5. Orchestrator spot-checks diffs: git -C <worktree-path> diff main --stat
6. Orchestrator runs /simplify in the worktree (or dispatches agent to do so)
7. Review agent dispatched INTO the worktree (reads files and runs tests there)
8. After review passes: push worktree branch to origin and create PR
9. After PR merges: git checkout main && git pull origin main
10. Worktrees with no changes are automatically cleaned up
```

### Capturing worktree metadata

When an agent returns from worktree isolation, the Agent tool result includes:
- **Worktree path** — the directory where the isolated copy lives
- **Branch name** — the git branch the agent committed to

Record these alongside the MCP item ID. You need them for:
- Running `git -C <path> diff main --name-only` to get the changed files list
- Pointing the review agent at the correct directory
- Squash-merging the branch into local `main` after review

### Parallel worktree validation

When multiple agents return from parallel worktrees:

1. Capture each agent's worktree path and branch from the return metadata
2. Spot-check at least 2 diffs for insertion errors, scope violations, or
   unintended modifications
3. Run each worktree's test suite independently (or delegate to review agents)
4. After review passes, push each worktree branch and create a PR
5. Run the full test suite before pushing to catch integration issues
6. Clean up local branches after PRs merge

### Test baseline management

When dispatching parallel worktree agents, check if any task modifies shared
code (domain models, enums, database schema, test infrastructure). If so:

1. Dispatch the shared-code task **first** (not in parallel)
2. After it returns, run `./gradlew :current:test` AND `./gradlew :current:ktlintCheck` on main to establish a clean baseline
3. **Then** dispatch the remaining independent tasks in parallel

**Symptom of contamination:** Multiple agents report "N pre-existing test
failures unrelated to our changes" on the same tests. That's contamination
from a parallel task, not pre-existing failures. Always verify agent test
reports with a direct test run after all agents complete.

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
