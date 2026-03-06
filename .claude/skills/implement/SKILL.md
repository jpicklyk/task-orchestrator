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

Sync main and create a working branch before any implementation begins.

```bash
git checkout main
git pull origin main --tags
git checkout -b <branch-name>
```

**Branch naming:**
- `feat/<short-description>` — feature-implementation items
- `fix/<short-description>` — bug-fix items
- `fix/<grouped-description>` — batch of related bug fixes
- `chore/<short-description>` — tech debt, refactoring, observations

For autonomous batch work with unrelated items, create separate branches. Use
worktree isolation (`isolation: "worktree"` on the Agent tool) when dispatching
multiple implementation agents in parallel to prevent file conflicts.

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

**After implementation completes:**
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

The review agent:
1. Reads the review-quality skill
2. Uses `get_context(itemId=...)` to load the item's notes and review-phase requirements
3. Reads the changed files directly
4. Runs the test suite
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

## Step 6 — Commit and PR

After review passes, commit the changes and create a PR.

### Commit

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

For batch work with multiple items in one branch, use a single commit or logical
commits per item — whichever tells a clearer story in the git log.

### Push and PR

```bash
git push origin <branch-name>
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
3. **Per-group pipeline** — each group goes through Steps 2-6 independently
4. **Report at the end** — summarize all PRs created, any items that couldn't be
   processed, and any review failures that need user attention

If any item in the batch hits a review failure, continue processing other items
and report all failures together at the end.

---

## Worktree Isolation

When dispatching multiple implementation agents in parallel, use `isolation: "worktree"`
on the Agent tool. Each agent gets an isolated copy of the repository, preventing file
conflicts, accidental cross-cutting changes, and test baseline contamination.

**Dispatch pattern:**
```
Agent(
  prompt="...",
  model="sonnet",
  isolation="worktree",
  subagent_type="general-purpose"
)
```

**Scoping rules — include in every parallel subagent prompt:**
- "Only modify files directly related to your task"
- "Do not bump versions, modify shared config, or edit files outside your scope"
- Cross-cutting changes (version bumps, shared config) are handled by the
  orchestrator after all agents return

**Validation after agents return:**
1. Review each worktree's changes — the Agent tool returns the worktree path and
   branch when changes are made
2. Spot-check at least 2 diffs for insertion errors, scope violations, or unintended
   modifications
3. Merge worktree branches sequentially into the working branch, resolving any conflicts
4. Run the full test suite once after all merges to catch integration issues
5. Worktrees with no changes are automatically cleaned up; merged worktrees should be
   removed after successful integration

**When NOT to use worktrees:**
- Single-agent dispatch (no isolation needed)
- Tasks that depend on each other's file changes (use sequential dispatch instead)
- Pure MCP operations with no file modifications (e.g., materialization subagents)

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
