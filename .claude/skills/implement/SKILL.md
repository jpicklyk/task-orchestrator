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

## Route

Run Step 1 below, then read the one file in your row before Step 2, and no other path file. The
sections under "Every path" are already in front of you; the path files cite them by name.

| Tier from Step 1 | Read now, and only this |
|---|---|
| Direct | [direct.md](direct.md) |
| Delegated, one item | [delegated.md](delegated.md) |
| Parallel, or Delegated with 2+ ready items | [wave.md](wave.md) (it runs `/task-orchestrator:run-wave`) |
| A coordinator handed a run-wave review (`review: "handoff"`) | [wave.md](wave.md), Step 5 |
| Inside a wave, a dispatch run-wave does not generate (no run-wave, a one-off fix agent, an arbitration re-dispatch) | [hand-dispatch.md](hand-dispatch.md), as well as wave.md |
| Resuming | the file and step the Resume table names |

Each path file keeps the same step numbers (Step 2 Branch through Step 6 Finalize).

---

## Step 1 — Assess the Work

Load the item(s) and determine the execution tier and interaction mode. Read every answer
through "Envelope" below.

For each item, call `items_read` `context` (`itemId`) to understand:
- Current role and gate status (`gateStatus.canAdvance`, `gateStatus.missing`)
- Item type (feature-implementation, bug-fix, etc.)
- Existing notes already filled
- Dependencies and blocked status

When the user or an item references a PR or commit as already landed, verify its merge state
before relying on it — `gh pr view <n> --json state,mergeCommit` plus `git log origin/main` —
and confirm the working tree is current ("Base freshness" below) before any pass
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

Delegated with ≥2 ready items, or Parallel → run `/task-orchestrator:run-wave` (the Route table's
`wave.md` row).

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

## Every path

### Envelope

Call shapes are in `claude-plugins/task-orchestrator/skills/_generated/call-shapes.md` and field semantics in `current/docs/api-reference.md`; ids below are 8-hex shorts (any id field also takes the full UUID). Read every answer through the envelope: `ok`, then `data.results[i]` (`ok` or `error`). When a write element fails, the call-level `error` is only `partial_failure` (no detail, no fix): read `code`, `kind`, `detail` and `fix` from `data.results[i].error`. A read (`select`) call reports its failure in the call-level `error`. A dispatch-issued seat token goes in the top-level `seatToken` of every call, reads included; it is never printed, committed or written to a note.

### Base freshness

Sync local main before any implementation begins:

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
`origin/main`. A stale base makes adopted work look missing.

### Verification command

"Run tests" means `:current:test` AND `:current:ktlintCheck` (CI enforces both). The orchestrator
runs them through the lock helper, which serializes gradle and prints a final
`EXIT=<n> (log: <path>)` line. Read that line, and read the log when it is non-zero:

```
$env:TO_GRADLE_OWNER='orchestrator'; & "<wt>\.claude\skills\implement\references\gradle-locked.ps1" -Worktree "<wt>" -Tasks ":current:test",":current:ktlintCheck"
```

(PowerShell tool; this is `run-profile.json` `wave-build`; `<wt>` is the checkout the work is in:
the main checkout, the item's worktree or the feature worktree.) Without the helper: redirect
gradle to a log, capture `EXIT=$?`, then read the log. Never pipe gradle into `tail`: the pipe
reports the pager's exit code. On a lint failure run `:current:ktlintFormat`, then re-run both.
The helper refuses the suite, `ktlintCheck` and `--rerun-tasks` from any seat (EXIT=98);
`--rerun-tasks` is for the run after a dependency or Gradle upgrade only. The helper's header
explains a held or reclaimed lock (`LOCK HELD`, `LOCK RECLAIMED`).

### Planning rules

**Plans are spec inputs.** Every file path, tool contract, or call sequence a plan names must
cite its authoritative location and be verified on disk or in source before the plan is
approved — child items' specification notes are generated from the plan and inherit its errors
silently (spec-quality, "Cite Contracts, Don't Restate Them"). This reminder lives here because
spec-quality is reached via note `guidance` pointers that plan mode does not traverse.

**Plans carry the State matrix.** For each item that changes user-visible runtime behaviour, the
plan's Verification / Test strategy section carries (or points at) the item's State matrix, or
one `State matrix: n/a - <reason>` line (spec-quality, "State matrix"). Same reason as above:
plan mode does not traverse the guidance pointer that would surface it.

**Seat timing for `needs-test-author` items.** The queue-phase `test-plan` note is filled by the
**planning seat** (the orchestrator on Direct tier, the plan author on Delegated/Parallel), using
the `test-author` skill's scenario- and oracle-derivation sections, **before** the queue-to-work
`start`: it gates work entry like any required queue note. It is not the **test author** seat,
which writes test code and fills `test-manifest` at work phase.

**Gate and lease failures.** The gate rejects an advance with missing required notes
(`gate_blocked`, with `data.results[0].error.detail.missing[]`): fill them and retry. Do not
confuse this with resource-lease contention. A queue→work `advance` can also fail
`resource_unavailable` (`error.kind: "transient"`): a resource a trait on this item declares
(`resources:`) is held by another item. Filling notes will not fix it. Work a different item and
retry later (`error.retryAfterMs` is a hint), or report the keys in `error.detail.resources[]` to the user.

### Arbitration — Red Author-Authored Tests

Applies to items carrying `needs-test-author` with a separate test author (not Direct). When a
test the author wrote comes back red against the implementation, the test author does not
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

**A red author-authored test is not a broken build.** Route it to arbitration above, never to a
generic fix agent. If arbitration does not resolve before build verification needs to conclude,
hold the child in work and report the unresolved red test rather than forcing green.

### Merge gate

**Commit form** (orchestrator commits; stage only the files the change owns):

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

**CHANGELOG check and Docs line.** Confirm the branch carries the `[Unreleased]` bullet written in
Step 4c, or that the PR body will state `Changelog: none (<why>)`.
Both PR-body lines come from the diff, never from the plan: run
`git diff --name-only <base>...HEAD` and (a) list every changed doc path from that output on the
body's Docs line; (b) require `CHANGELOG.md` in that output unless the body states
`Changelog: none (<why>)`. Recompute both lines after any merge round (a base merge or
update-branch), before merge.

**The gate**, once the PR is open:
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
  cleanup. Then `git checkout main`, `git pull origin main`, and delete the branch (and remove
  the worktree, if the work had one). Local `main` always tracks `origin/main` — no divergence,
  no `reset --hard` needed.

### After merge

**Post-merge live acceptance.** If, after the merge, the only non-terminal children of the item
or feature are live checks that need a refreshed plugin cache or a user opt-in, split them off
exactly as run-wave `references/post-run.md` Step 7b "Post-merge live acceptance" says, (a) to
(d), BEFORE completing the item or feature: a terminal parent takes no new children. For an item
that is not a feature, put the item in the feature's place and its parent as the holder's parent.

**Wave-boundary red-proofs** (run in the background; they gate neither the merge nor the
terminal transition). Only the bug-fix revert ran before merge. The decision-point budget
mutations (each planner's `red-proof-shape`; policy and skip categories in rule `test-author`
section 5) run now, per run-wave `references/post-run.md` Step 7a items 1-6, in ONE batch for the
wave parent (for a single item, the item is the wave parent): one scratch worktree at the merged
`main` SHA, each mutation applied with `references/patch-anchored.py` (`DRY=1` first) and run
scoped to the test classes `test-manifest` maps to it (`-TestFiles`, `TO_GRADLE_OWNER=orchestrator`),
one full suite only for a survivor, results recorded once on the wave parent's
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

Dispatch the runner with the Agent `description` `red-proofs:<wave parent short>` (the Label rule
of run-wave `post-run.md`) and an explicit `model` and `effort`. When there is a run id, record
its meters as `post-run.md` Step 7a item 7 says; with none, write no meters line and say so in
the report.

### Retrospective

The hook fires on a successful `runs_write` `close` (or the Stop backstop for a closed run
without the `retro` mark), never on a terminal transition (mode
`extensions.claude-code.retrospective.mode`). In `dispatch` mode follow its directive; in `nudge`
mode or outside a run, run `/session-retrospective` by hand.

---

## Resuming In-Progress Work

Tier classification happens at Step 1 even when resuming. Classify the tier from the item's tags,
file scope, and note state, then read that tier's path file (the Route table) and resume at the
step below. Always call `items_read` `context` (`itemId`) first to determine exact state.

| Current role | Resume from (in the tier's path file) |
|-------------|-------------|
| queue (notes filled) | Step 3 — advance and proceed |
| queue (notes missing) | Step 3 — fill missing notes |
| work (in progress) | Step 4 — check implementation state |
| work (notes filled) | Step 4c — advance out of work |
| review | Step 5 — run review |
| terminal | Already done — report status |
| an open run found (run-wave preflight F7) | `wave.md`: `/task-orchestrator:run-wave --resume <runId>` |

Steps 2-6 moved out of this file: a citation of "`/implement` Step 5" or "Step 6" written before
the split means the same step in the tier's path file (`wave.md` for Parallel and for any run).
