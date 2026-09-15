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
  Dispatch contract: <ABSOLUTE path from the contract's Header "Contract path" line, e.g.
  D:\Projects\task-orchestrator\plans\<slug>.md> — read it first; it wins on conflict with
  this prompt. It lives in the main checkout, not in the worktree above.
  Scope (modify ONLY these files): <explicit list>

  After making changes, commit exactly as the contract's "Commit discipline" slot states.
  Run gradle only through the contract's "Compile self-check" slot — the orchestrator owns
  full build verification.
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

### Committing in a shared worktree

One worktree means **one git index**, shared by every agent in the wave. `git add` stages into
that shared index, so a bare `git commit` sweeps up whatever another agent staged in flight —
the mechanism behind three cross-contaminated commits and one dropped commit in the 2026-09 bug
wave (proposal `568e7f7e` / #301). Three consequences:

- **Commit by pathspec, then verify.** `git commit --only -- <owned paths>` commits the named
  paths regardless of what else is staged, and `git show --stat HEAD` proves it did; a mismatch
  is undone with `reset --soft HEAD~1` and re-committed the same way. The exact sequence agents
  are given — staging, commit, verification, recovery — is the **Commit discipline** slot of
  [`references/dispatch-contract-template.md`](references/dispatch-contract-template.md);
  dispatch prompts point there rather than restating it, so every agent in a wave uses one form.
- **`--only` scopes by PATH, not by hunk.** It protects you from other agents' *files*, not from
  unreviewed changes inside your own. A `ktlintFormat` pass that reformatted a neighbouring file
  is excluded because that file is unowned; an unrelated edit inside a file you own is not. Read
  the diff of your own paths before committing whenever a formatter has run.
- **There is no git equivalent of the gradle lock.** Gradle contention is serialized by a lock
  helper the orchestrator provides; the git index has no such gate, so path scoping is the
  entire safety mechanism, not a belt-and-braces addition to one.

### Capturing per-child commit metadata

Before each dispatch, capture HEAD:

```bash
git -C <FEATURE_WORKTREE> rev-parse HEAD
```

After each dispatch returns (agent has committed), capture HEAD again. The two SHAs bound
what that child committed — used for the ownership check below, **not** for scoping the
review agent (see "Review per child").

```
| Child UUID | Agent ID    | Pre-SHA  | Post-SHA | Test-Pre-SHA | Test-Post-SHA | Status        |
|------------|-------------|----------|----------|--------------|---------------|---------------|
| <uuid>     | agent-g1    | abc123   | def456   | <test-pre>   | <test-post>   | reviewing     |
| <uuid>     | agent-g2    | def456   | ghi789   | (no needs-test-author) |    | done          |
```

The `Test-Pre-SHA`/`Test-Post-SHA` columns record the test author's pre/post commit range
(Step 4b of `/implement`) for items carrying `needs-test-author`. They stay blank/marked "(no
needs-test-author)" for items that don't carry the trait. Their job is the disjointness check
(impl-range never touches `src/test/**`, test-range never touches `src/main/**`) — a per-commit
`git show --stat <sha>` question, answered from the map rather than from a review diff.

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

Review agents read from the shared worktree, scoped to the child's **owned files**:

```bash
git -C <FEATURE_WORKTREE> diff <base-sha>..HEAD -- <that child's owned files>
```

Not its commit range. In a shared worktree the commits between a child's pre- and post-SHA
interleave other children's work, and a later fix-up, formatter or orchestrator fixture-repair
commit falls outside the range entirely — so a SHA range both over-reports (other streams) and
under-reports (later edits to the same files). The owned-file diff is exactly the surface that
child is accountable for, at whatever state the branch has reached. The pre/post map is still
captured, for the ownership check above. The rule agents and reviewers are handed is the
**Review scoping** slot of
[`references/dispatch-contract-template.md`](references/dispatch-contract-template.md); see
Step 5 of `/implement` for the agent template.

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
