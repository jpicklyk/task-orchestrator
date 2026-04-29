# Worktree Strategy

## Two patterns

| Pattern | When to use | Branch | PR scope |
|---------|-------------|--------|----------|
| **Shared feature worktree** | Parallel-tier feature with multiple children | `feat/<feature-slug>` (one branch) | One PR at parent finalization |
| **Direct working branch** | Direct or Delegated tier (single item) | `<type>/<slug>` (one branch) | One PR per item |

The legacy "isolation per agent dispatch" pattern (`isolation: "worktree"` on every
Agent call) is **deprecated** for `feature-implementation` parents — it produced N PRs
per feature, with cross-PR contamination friction (see retro `a7f6024f`).

## Shared feature worktree (Parallel tier)

### Setup (Step 2 of /implement)

```bash
git checkout main && git pull origin main

FEATURE_SLUG=<short-feature-description>     # e.g. issue-117-followup
FEATURE_BRANCH=feat/$FEATURE_SLUG
FEATURE_WORKTREE=.claude/worktrees/feat-$FEATURE_SLUG

# Resume detection — if branch/worktree already exist, reuse them.
if git show-ref --verify --quiet "refs/heads/$FEATURE_BRANCH"; then
  echo "Resuming existing feature branch $FEATURE_BRANCH"
else
  git branch "$FEATURE_BRANCH" main
fi
if [ ! -d "$FEATURE_WORKTREE" ]; then
  git worktree add "$FEATURE_WORKTREE" "$FEATURE_BRANCH"
fi
```

Record `$FEATURE_WORKTREE` and `$FEATURE_BRANCH` for the duration of the feature run.

### Dispatching child agents

Agents share the feature worktree. **Do NOT use `isolation: "worktree"`** on the Agent
tool — that spawns a separate worktree per dispatch (the deprecated pattern).

```
Agent(
  prompt="""
  Working directory: <FEATURE_WORKTREE>
  Branch (already checked out): <FEATURE_BRANCH>
  Scope (modify ONLY these files): <explicit list>

  After making changes, commit with a descriptive message. Do NOT run gradle —
  the orchestrator owns build verification.
  """,
  model="sonnet",
  subagent_type="general-purpose"
)
```

**Parallel dispatch:** when children touch non-overlapping files, dispatch in parallel
waves. The orchestrator must enforce file scope per agent — overlapping files in a
shared worktree cause "second agent reads the file after first agent committed" mid-flight
confusion.

**Sequential dispatch:** when children share files, or when they have dependency edges,
dispatch sequentially.

### Capturing per-child commit metadata

Before each dispatch, capture HEAD:

```bash
git -C <FEATURE_WORKTREE> rev-parse HEAD
```

After each dispatch returns (agent has committed), capture HEAD again. The diff between
the two SHAs is exactly that child's work — used for scoping the review agent.

```
| Child UUID | Agent ID    | Pre-SHA  | Post-SHA | Status        |
|------------|-------------|----------|----------|---------------|
| <uuid>     | agent-g1    | abc123   | def456   | reviewing     |
| <uuid>     | agent-g2    | def456   | ghi789   | done          |
```

### Orchestrator-owned build verification

After each parallel wave completes (or between sequential children):

```bash
./gradlew -p <FEATURE_WORKTREE> :current:test
./gradlew -p <FEATURE_WORKTREE> :current:ktlintCheck
```

If failure: dispatch a fix agent into the same worktree before advancing any child to
review. The orchestrator owns these invocations because gradle daemon and `build/` cache
are per-directory — parallel `gradlew test` against the same worktree will queue, corrupt
cache, or hit file locks.

### Review per child

Review agent reads from the shared worktree, scoped to one child's commit range
(`<pre-sha>..<post-sha>`). See Step 5 of `/implement` for the agent template.

### Finalization

When all children reach terminal:

1. Final test + ktlint pass on the feature worktree.
2. Fill parent feature's notes (implementation-notes, session-tracking, review-checklist).
3. Push `<FEATURE_BRANCH>` and open one PR.
4. After PR merges:
   ```bash
   git checkout main && git pull origin main
   git worktree remove <FEATURE_WORKTREE>
   git branch -D <FEATURE_BRANCH>
   ```

## Direct / Delegated tier (single item)

Orchestrator creates a working branch on the main directory:

```bash
git checkout -b <type>/<slug>
```

Implements directly (Direct) or dispatches one subagent (Delegated). After review,
push and PR per item — same as legacy flow.

## Test baseline management

When dispatching parallel children, check if any child modifies shared code (domain
models, enums, database schema, test infrastructure). If so:

1. Dispatch the shared-code child **first** (sequential, not parallel).
2. After it returns and commits, run the orchestrator-owned build verification on the
   feature worktree to establish a clean baseline.
3. **Then** dispatch the remaining independent children in parallel.

**Symptom of contamination:** Multiple agents report "N pre-existing test failures
unrelated to our changes" on the same tests. That's contamination from a previous
parallel commit, not pre-existing failures. The orchestrator-owned verification step
between waves prevents this by definition.

## Why this design

- **One PR per feature** — coherent reviewability, one CI cycle, no cross-PR contamination.
- **Shared filesystem, scoped scopes** — agents work in parallel on different files;
  the orchestrator enforces non-overlap.
- **Build serialization at the orchestrator** — gradle daemon constraints respected
  without slowing agents.
- **Per-child commits** — review still scopes to individual children; commit history
  in the merged PR shows the per-child trajectory.
- **Cleanup is one operation** — one worktree to remove, one branch to delete.
