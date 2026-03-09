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

Load the item(s) and determine the operating mode.

For each item, call `get_context(itemId=...)` to understand:
- Current role and gate status
- Schema tag (feature-implementation, bug-fix, etc.)
- Existing notes already filled
- Dependencies and blocked status

**Mode determination:**

| Signal | Mode |
|--------|------|
| User says "work with me on", "let's plan", or similar collaborative language | **Collaborative** — user participates in planning and key decisions |
| Single large feature with multiple child items or significant blast radius | **Collaborative** — too much risk for autonomous execution |
| Small bug fix, tech debt cleanup, or isolated change with clear scope | **Autonomous** — agent handles the full pipeline |
| Multiple bug fixes or small items | **Autonomous batch** — agent groups related items and processes them |
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
a single non-isolated agent), create a local working branch:

```bash
git checkout -b <branch-name>
```

This branch stays local — it will be squash-merged into local `main` after
review, not pushed directly to origin. See Step 6 for the squash-merge flow.

**If using worktree isolation**, skip branch creation — each worktree agent gets
its own isolated branch automatically. See Worktree Isolation below. Worktree
branches are also local-only and get squash-merged into `main` after review.

**Branch naming** (for local working branches):
- `feat/<short-description>` — feature-implementation / feature-task items
- `fix/<short-description>` — bug-fix items
- `fix/<grouped-description>` — batch of related bug fixes
- `chore/<short-description>` — tech debt, refactoring, observations

---

## Step 3 — Queue Phase: Planning

Progress through the queue phase using the item's schema-defined gates. The existing
orchestration skills handle the specifics — this step composes them.

Use `get_context(itemId=...)` to see the item's `expectedNotes` and `guidancePointer`
for the current phase. These are driven by the schema configuration and will reflect
whatever notes are required at the queue gate.

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

Implement the changes and fill the work-phase notes required by the schema.

Use `get_context(itemId=...)` to see the work-phase `expectedNotes` and their
`guidancePointer` values. Fill each required note following its guidance.

Follow the delegation model from your output style (model selection, return formats,
UUID inclusion). The key decisions at this step are:

- **Single item, simple change:** delegate to one implementation subagent or
  implement directly.
- **Multiple child tasks, independent:** dispatch parallel subagents with worktree
  isolation (see Worktree Isolation below).
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
5. Advance the item: `advance_item(trigger="start")` to move to review

---

## Step 5 — Review Phase: Independent Review

Dispatch a **separate** review agent. The agent that implemented the code must not
review its own work.

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
4. Runs the test suite (from the worktree if isolated)
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

## Step 6 — Commit and Squash-Merge to Main

After review passes, commit the changes and squash-merge into local `main`.
PRs to GitHub are batched — multiple completed items accumulate on local `main`
before a single PR is created.

### Commit on the working branch

**If using worktree isolation**, commit from the worktree:
```bash
git -C <worktree-path> add <specific-files>
git -C <worktree-path> commit -m "..."
```

**If NOT using worktree isolation**, commit on the local working branch as normal.

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

### Squash-merge into local main

After committing on the working branch (or worktree branch), squash-merge into
local `main`:

```bash
git checkout main
git merge --squash <branch-name>       # or: git merge --squash <worktree-branch>
git commit -m "<type>(<scope>): <description>"
git branch -D <branch-name>            # delete the local working branch
```

For worktree branches, use the branch name returned by the Agent tool. The
worktree directory is cleaned up automatically after the branch is deleted.

Local `main` now has the squashed change. Repeat for more items — they accumulate.

### Push and PR (batched)

When ready to publish one or more accumulated changes to GitHub, create a PR
branch from local `main`:

```bash
git checkout -b <pr-branch-name>       # e.g., feat/batch-validation-improvements
git push -u origin <pr-branch-name>
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

After the GitHub PR is merged, sync local main:
```bash
git checkout main
git pull origin main
git branch -D <pr-branch-name>         # delete the local PR branch
```

**When to create a PR** — use judgment:
- After completing a logical unit of work (a feature, a batch of fixes)
- When local `main` has accumulated enough changes to warrant publishing
- The user may explicitly request a PR at any point

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
   implementation → capture worktree metadata → simplify → review (in worktree) → squash-merge to main
4. **Track all worktrees** — maintain a table mapping item UUID → worktree path →
   branch → status (implementing / reviewing / squash-merged / failed)
5. **Squash-merge each** — after review passes, squash-merge each worktree branch
   into local `main`. Run the full test suite after each merge to catch integration issues.
6. **Report at the end** — summarize items completed, any review failures, and whether
   a PR to GitHub is ready

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

Review happens in the worktree. After review passes, squash-merge into local
`main` — do NOT push worktree branches to origin.

```
1. Orchestrator dispatches agent with isolation: "worktree"
2. Agent works in isolated worktree, commits to worktree branch
3. Agent returns → result includes worktree path and branch name
4. Orchestrator captures worktree metadata (path, branch, changed files)
5. Orchestrator spot-checks diffs: git -C <worktree-path> diff main --stat
6. Orchestrator runs /simplify in the worktree (or dispatches agent to do so)
7. Review agent dispatched INTO the worktree (reads files and runs tests there)
8. After review passes: squash-merge worktree branch into local main
9. Worktrees with no changes are automatically cleaned up
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
4. Squash-merge each reviewed worktree branch into local `main` sequentially
5. Run the full test suite after all merges to catch integration issues
6. Delete worktree branches after successful squash-merge

### Test baseline management

When dispatching parallel worktree agents, check if any task modifies shared
code (domain models, enums, database schema, test infrastructure). If so:

1. Dispatch the shared-code task **first** (not in parallel)
2. After it returns, run `./gradlew :current:test` on main to establish a clean baseline
3. **Then** dispatch the remaining independent tasks in parallel

**Symptom of contamination:** Multiple agents report "N pre-existing test
failures unrelated to our changes" on the same tests. That's contamination
from a parallel task, not pre-existing failures. Always verify agent test
reports with a direct test run after all agents complete.

---

## Resuming In-Progress Work

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
