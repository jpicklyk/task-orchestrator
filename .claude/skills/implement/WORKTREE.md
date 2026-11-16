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

FEATURE_SLUG=<slug>
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
captured, for the ownership check ("SHA tracking table" below). The rule agents and reviewers are
handed is the **Review scoping** slot of
[`references/dispatch-contract-template.md`](references/dispatch-contract-template.md); see
Step 5 of `/implement` for the agent template.

## Hand-dispatching a Parallel wave

Read this before the first dispatch that `/task-orchestrator:run-wave` does not generate: a wave
run without run-wave, a one-off fix agent, or an arbitration re-dispatch (`/implement` Step 4b).
Under run-wave, seat prompts are generated, the planner return is schema-checked and ownership is
checked by `post-run.md` Step 1, so none of this section applies to its seats.

### Implementer dispatch template

Agents share the feature worktree. **Do NOT use `isolation: "worktree"`** on the Agent
tool — that spawns a separate worktree per dispatch (the deprecated pattern).

```
Agent(
  prompt="""
  Working directory: <feature-worktree-path>
  Branch (already checked out): feat/<feature-slug>
  Dispatch contract: <ABSOLUTE path from the contract's Header "Contract path" line, e.g.
  D:\Projects\task-orchestrator\plans\<slug>.md> — read it first; it wins on conflict with
  this prompt. It lives in the main checkout, not in the worktree above.
  Scope (modify ONLY these files): <explicit list> — the same list as your row in the
  contract's File ownership slot.
  Do NOT create or modify any file under src/test/** — test authoring is a separate,
  independent dispatch (Step 4b) on items carrying needs-test-author. If your change
  surfaces a needed test update, report it in your return; never edit the test yourself.

  Before returning: run the compile self-check exactly as the contract's "Compile self-check"
  slot pins it, and handle its outcome by that slot's rules. Then commit exactly as the
  "Commit discipline" slot states, including the post-commit verification.
  Do NOT run :current:test or :current:ktlintCheck — the orchestrator owns full build
  verification.
  """,
  model="<dispatch.model, else the implementation class>",
  effort="<dispatch.effort, else the implementation class>",
  subagent_type="<dispatch.agent when the item's resolved profile names one, else general-purpose>"
  // NOTE: no isolation parameter — agents share the feature worktree
)
```

`model` and `effort` follow `task-orchestrator:orchestrate` "Dispatching an item's phase owner"
(the profile of the phase dispatched INTO, else the class in `task-orchestrator:model-effort`);
`dispatch-model-guard` denies a dispatch that omits either.

**Why the prompt points at slots instead of carrying the commands.** The pinned self-check is fast
and catches type and signature errors that gradle's incremental cache can mask until the
orchestrator's later run, and its `ktlintFormat` step prevents lint fix-up commits. But the
invocation's exact argument form, the foreign-file early exit and the retry bound only work if
every agent gets them identically: a re-typed nested invocation flattens a `[string[]]` argument,
and agents without the early exit burn their retries against another agent's mid-refactor file. A
prompt that restates the command drifts from the contract; a prompt that points at the slot
cannot. The same applies to the commit step: never tell an agent to "commit your changes with a
descriptive message" in a shared worktree; the Commit discipline slot is the only safe form.

**Parallel dispatch:** when children touch non-overlapping files, dispatch in parallel
waves. The orchestrator must enforce file scope per agent — overlapping files in a
shared worktree cause "second agent reads the file after first agent committed" mid-flight
confusion.

**Sequential dispatch:** when children share files, dispatch sequentially without a BLOCKS edge (under run-wave, per-file locks serialize them; declare `Shared file, lock-serialized: <path>` in both scopes); BLOCKS edges are for semantic dependencies, and dependency edges are
classified by `run-wave` (in-run vs cross-run).

### Test-author dispatch prompt

Point the agent at the **Test author protocol** slot of the wave's dispatch contract, named by the
absolute Contract path from its Header. That slot states the declarations block, the `src/main`
tool ban, the `keys=` filter, stop-and-ask, fixture invariants, surface labels, commit form and
manifest fields in full. On a Delegated-tier run with no contract file, paste that slot into the
prompt instead of paraphrasing it. The declarations block is filled by a separate, non-author
declarations-extractor seat, and the orchestrator scans and strips it of implementation prose
before the author sees it (rule `declarations-extractor` states the seat's accuracy contract and
the scan). Pass `model` and `effort` from the item's `dispatchBySeat` test-author entry.

### Planner return fields

A hand-dispatched planning seat (`/implement` Step 3) returns these eight fields, each required
(`none` is a valid value), in the block the contract's Planning seat return template fixes. The
fields are the budget: the return may exceed any line budget when the fields have content, and
trimming a field to fit a target length is the failure this seat exists to prevent. Give the seat
the contract by its absolute path (the Header's Contract path line), never a relative
`plans/...`, which does not resolve from the worktree.

Under run-wave the `planner-v1` stage returns a different, schema-validated shape
(`workflows/implement-wave.js` `OUTPUT_SCHEMAS['planner-v1']`): it has no
`cross-stream-file-overlaps` and adds `proceed`, `blockReason`, `decisions`, `docFiles`,
`existingTestEdits` and `sweep`. The schema is authoritative there; this table is the
hand-dispatch contract only.

| Field | Semantics |
|---|---|
| `diagnosis-corrections` | What the diagnosis/task-scope got wrong or missed, with current `file:line` — or `none`. |
| `defect-class-siblings` | Every other site sharing the root-cause pattern (same cast/fallback idiom, same predicate, same wildcard or character class, same error-classification path, other readers or writers of the field), found by a named sweep (command + scope + hit count). Mark each one `frozen as D#` or `deferred: <reason>`, or return `none (sweep: <command>)`. |
| `cross-stream-file-overlaps` | Files this stream must write that another stream in the same wave also touches, or `none`. In one shared worktree a shared file is lock-serialized and is never by itself a reason for a BLOCKS edge. |
| `missing-api-or-seam` | A surface the fix needs that does not exist yet, with the exact proposed NEW signature — or `none`. |
| `test-plan-status` | Whether `test-plan` is filled and frozen (`filled (<n> chars)`) or still open, and why. |
| `main-files` | The stream's production files (`src/main`), comma-separated. |
| `test-files` | The stream's NEW test files (author-owned), comma-separated. |
| `red-proof-shape` | The item's red-proof budget (policy: served `test-author` rule, section 5): for a bug-fix, the revert-the-fix red-proof on the regression test; for new guard, gate, transition, classification, boundary or security behaviour, up to about 5 decision-point mutations (a cap, one per distinct decision point, one batched round), each labelled `EXISTING-SURFACE` (a plain revert gives behavioural red) or `NEW-SURFACE` (the fix introduces the surface; give the narrowest-revert recipe, typically revert only the call sites, or `no behavioural red possible, reviewer verifies <X>`); or `skip: <category>` for a test-only item, pure refactor, migration, or glue/config/docs. Not one recipe per scenario. |

### SHA tracking table

Before each dispatch, capture HEAD; after the agent returns (it has committed), capture it again:

```bash
git -C <FEATURE_WORKTREE> rev-parse HEAD
git -C <FEATURE_WORKTREE> diff <pre-sha>..<post-sha> --name-only
```

The two SHAs bound what that child committed. They feed the ownership two-range check and the
contract's **Review scoping** commit map, **not** the scope of the review agent (see "Review per
child").

```
| Child UUID | Agent ID    | Pre-SHA  | Post-SHA | Test-Pre-SHA | Test-Post-SHA | Changed files | Status        |
|------------|-------------|----------|----------|--------------|---------------|---------------|---------------|
| <uuid>     | agent-g1    | abc123   | def456   | <test-pre>   | <test-post>   | <file list>   | reviewing     |
| <uuid>     | agent-g2    | def456   | ghi789   | (no needs-test-author) |     | <file list>   | done          |
```

The `Test-Pre-SHA`/`Test-Post-SHA` columns record the test author's pre/post commit range
(Step 4b of `/implement`) for items carrying `needs-test-author`. They stay blank/marked "(no
needs-test-author)" for items that don't carry the trait. Their job is the disjointness check
(impl-range never touches `src/test/**`, test-range never touches `src/main/**`) — a per-commit
`git show --stat <sha>` question, answered from the map rather than from a review diff.

## Test baseline management

When dispatching parallel children, check if any child modifies shared code (domain
models, enums, database schema, test infrastructure). If so:

1. Dispatch the shared-code child **first** (sequential, not parallel).
2. After it returns and commits, run the orchestrator-owned build verification
   (`/implement` Step 4c) on the feature worktree to establish a clean baseline.
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
  without slowing agents. The daemon and the `build/` cache are per directory, so parallel runs
  against one worktree queue at the daemon, corrupt the cache or hit Windows file locks.
- **Per-child commits** — review still scopes to individual children; commit history
  in the merged PR shows the per-child trajectory.
- **Cleanup is one operation** — one worktree to remove, one branch to delete.
