# Worktree Isolation

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

## Dispatch pattern

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

## Worktree lifecycle

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

## Capturing worktree metadata

When an agent returns from worktree isolation, the Agent tool result includes:
- **Worktree path** — the directory where the isolated copy lives
- **Branch name** — the git branch the agent committed to

Record these alongside the MCP item ID. You need them for:
- Running `git -C <path> diff main --name-only` to get the changed files list
- Pointing the review agent at the correct directory
- Pushing the branch to origin after review

## Parallel worktree validation

When multiple agents return from parallel worktrees:

1. Capture each agent's worktree path and branch from the return metadata
2. Spot-check at least 2 diffs for insertion errors, scope violations, or
   unintended modifications
3. Run each worktree's test suite independently (or delegate to review agents)
4. After review passes, push each worktree branch and create a PR
5. Run the full test suite before pushing to catch integration issues
6. Clean up local branches after PRs merge

## Test baseline management

When dispatching parallel worktree agents, check if any task modifies shared
code (domain models, enums, database schema, test infrastructure). If so:

1. Dispatch the shared-code task **first** (not in parallel)
2. After it returns, run `./gradlew :current:test` AND `./gradlew :current:ktlintCheck` on main to establish a clean baseline
3. **Then** dispatch the remaining independent tasks in parallel

**Symptom of contamination:** Multiple agents report "N pre-existing test
failures unrelated to our changes" on the same tests. That's contamination
from a parallel task, not pre-existing failures. Always verify agent test
reports with a direct test run after all agents complete.
