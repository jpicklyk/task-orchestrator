---
name: implement
description: End-to-end workflow for taking MCP work items from backlog to merged PR. Handles git branching, schema-driven planning, implementation, independent review, and PR creation; planning and review follow each note's `skillPointer`. Use when a user says "implement this", "work on this item", "fix these bugs", "pick up the next task", "create a PR for this", "go through the backlog", or references specific MCP item IDs for implementation.
user-invocable: true
---

# Implement

End-to-end workflow for taking MCP work items from queue to PR: repo mechanics and routing live
here; planning and review follow each note's `skillPointer` (a pointer that is a served rule key is
fetched as a rule, any other pointer is invoked as a skill; mechanics in `schema-workflow`), and
process rules are served rules (`docs_read` `get`, `kind` `rule`) that each section names by key.

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

**Execution tier** — classify by this table (canonical source shared with the `task-orchestrator:orchestrate` skill; rendered from the `tiers` in `dispatch-policy.json` by `_fragments/generate.mjs`, never hand-edited):

<!-- BEGIN GENERATED:tier-classification | source: claude-plugins/task-orchestrator/dispatch-policy.json (tiers) · regen: node claude-plugins/task-orchestrator/_fragments/generate.mjs -->
| Criteria | Tier | Pipeline |
|----------|------|----------|
| 1-2 files, known fix, no migration/new API | **Direct** | Orchestrator edits, tests, reviews inline |
| 3-10 files, single logical unit, clear or explorable scope | **Delegated** | Single subagent, separate review agent |
| 11+ files, multiple independent work streams, dependency edges | **Parallel** | Worktree agents, full pipeline |

**Force-UP signals** (bump tier regardless of file count):
- Database migration -> min Delegated
- New public API surface -> min Delegated
- Multiple independent work streams -> Parallel
- User says "let's plan" / collaborative language -> min Delegated

**Force-DOWN signals:**
- User says "just fix it" / "quick" -> Direct (unless complexity contradicts)
- The item's type resolves to the `default` schema, or the item is schema-free -> eligible for Direct

Thresholds and signals are data: `tiers` in the plugin's `dispatch-policy.json`. When `.taskorchestrator/tier-policy.json` exists beside the project config, the table in force is the output of `node <plugin root>/scripts/tier-policy.mjs`.
<!-- END GENERATED:tier-classification -->

Delegated with ≥2 ready items, or Parallel → run `/task-orchestrator:run-wave` (the Route table's
`wave.md` row).

If the item's type has no schema, give it `quick-fix` for Direct tier; for Delegated/Parallel the `default` schema (when configured) catches it.

**Traits, before any dispatch.** Apply the item's traits per rule `trait-selection` before the
first dispatch:

```to-call
{"tool":"items_write","op":"update","args":{"id":"4b7d0e19","traits":["delegated"]}}
```

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
parent feature. Items materialized from one approved plan share one parent feature and one branch, whatever their wave count (`wave.md` Step 2); a split into several parents is named on the plan's `work-breakdown` note.

---

## Every path

### Envelope

Call shapes are in `claude-plugins/task-orchestrator/skills/_generated/call-shapes.md`; read every
answer per `current/docs/api-reference.md` "Response envelope". Ids below are 8-hex shorts (any id
field also takes the full UUID). A dispatch-issued seat token is handled per rule
`protocol.in-phase-seat` `claude:`.

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

"Run tests" means every `.taskorchestrator/run-profile.json` `verify` entry whose `seats` list
`orchestrator` and whose `appliesTo` matches the diff (`git diff --name-only <base>...HEAD`); the
run-profile wins. `wave-build` runs through the lock helper, which serializes gradle and prints a final
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

### CHANGELOG bullet

A user-visible change (server behaviour, the MCP or REST surface, config keys, plugin skills or
hooks) adds ONE bullet under `CHANGELOG.md`'s `## [Unreleased]` section, in its `### Added` /
`### Changed` / `### Fixed` subsection (plugin skill and hook changes go under `### Plugin`). The
bullet describes behaviour, not the diff, and carries no volatile counts. A docs-, process- or
chore-only change adds none; its PR body states `Changelog: none (<why>)`. `/prepare-release`
Step 8c folds these bullets into the release section.

### Live-owed check

Before the work-exit `advance`, apply rule `live-checks` "The Live-owed hold".

- A `live-owed` cell on server behaviour runs `/deploy_to_docker --reuse` from the checkout
  under test, reconnects (`/mcp`), exercises the changed capability through the tools against
  the spec note's acceptance criteria, and records `run <date>: <result>` on its Live-owed
  entry. A change with a migration is never deployed to the shared data volume before merge:
  its entry is `deferred: <holder id>` (rule `live-checks` "Post-merge holder").

### Planning rules

**Plans are spec inputs.** Plan mode skips note guidance, so verify every path and contract a plan
names in source, as the planning note's `skillPointer` requires (a served rule key is fetched as a
rule, any other pointer is invoked as a skill; see `schema-workflow`).

**Plans carry the State matrix.** Each plan carries or points at the item's State matrix, or one
`State matrix: n/a - <reason>` line, per rule `live-checks`.

**Seat timing for `needs-test-author` items.** The planning seat fills and freezes `test-plan`
before the queue-to-work `start`, per rule `test-author` section 1 and the `test-plan` note's
`seat: planner`.

**Gate and lease failures.** On a `gate_blocked` reply, fill the notes in `detail.missing` from
their guidance and retry the transition. On `resource_unavailable` (a trait's lease is held
elsewhere), do not fill more notes or retry at once: work another item and retry later, after
`retryAfterMs` when it is set.

### Arbitration — Red Author-Authored Tests

A red test from a separate test author goes to the orchestrator's arbitration per rule
`arbitration`: its three dispositions, the `A<n>` ledger in the child's `orchestrator-confirmation`,
and the routing of a red author-authored test (never a broken build, never a generic fix agent).

### Merge gate

**Commit form** (orchestrator commits; stage only the files the change owns), per rule
`commit-discipline` `claude:`:

```bash
git -C <wt> add <paths>
git -C <wt> commit --only -m "<type>(<scope>): <title> [<short>]" -m "<why>" -m "Co-Authored-By: <the session's model attribution line>" -- <paths>
git -C <wt> show --stat HEAD
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

**Merge method** (`<method>` below). `--squash` for a PR with its own item (Direct, Delegated, a
per-item wave item). `--merge` for a shared-mode feature PR (`wave.md` Shared mode) when every
subject from `git log --first-parent --format=%s <base>..HEAD` is a conventional commit ending in
an 8-hex item tag (`[<item-short>]`); a fix seat's commit counts when it is written that way. Only
the branch's own commits count: main's commits merged in reach HEAD through a merge's second
parent, while the merge commit itself stays on the first-parent chain and must be tagged. Any other subject
(wip, untagged, a bare "address review") makes it `--squash`. Never rewrite the branch to clean it:
notes and run files record its SHAs (dispatch pre/post SHAs, repair SHAs, `fixed <sha>`
dispositions), and a rebase orphans them. The repo titles both merge and squash commits with the
PR title, so the PR title is the conventional-commit subject that lands on `main`.

**The gate**, once the PR is open:
- Wait on `gh pr checks <n> --watch --fail-fast` and merge only when every check is
  green. Never merge with a check pending. If auto-merge is armed, GitHub enforces "all green"
  only for REQUIRED checks, so this gate still applies to the rest.
- **Review-first arming.** Arm auto-merge (`gh pr merge <n> <method> --auto`), or merge, only
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
  last green check, run `gh pr merge <n> <method>`. Never directly merge a PR that is not
  `CLEAN` or has pending checks.
- Confirm `gh pr view <n> --json state` reports `MERGED` before any worktree or branch
  cleanup. Then `git checkout main`, `git pull origin main`, and delete the branch (and remove
  the worktree, if the work had one). Local `main` always tracks `origin/main` — no divergence,
  no `reset --hard` needed.
- **Final `complete`.** An item with its own PR (Direct, Delegated, a per-item wave item, a
  shared-mode parent) completes only after the `MERGED` check above. The closure notes are not
  rewritten to record the merge; the merged PR is the record. Two post-merge writes still happen:
  the steps this repo's `run-profile.json` `postRun` declares with `when` `post-merge`: the live acceptance
  line (rule `live-checks` "Post-merge holder", step (d)) and the wave parent's `red-proof-results` note
  (the item itself when it is a single item; rule `red-proofs`), per `post-run.md` Step 7a. Exceptions: a shared-mode child (`wave.md` Step 6) and
  a review-less run member (`post-run.md` Step 4).

### After merge

**Post-merge live acceptance.** Split off live checks that need a refreshed plugin cache or a user
opt-in as the `live-acceptance` step (rule `live-checks` "Post-merge holder", steps (a)-(e)) and run-wave
`references/post-run.md` Step 7b say, BEFORE completing the item or feature. For an item that is not a feature, put the item in the
feature's place and its parent as the holder's parent.

**Wave-boundary red-proofs** (the `red-proofs` post-merge step; background, they gate nothing) run per rule
`red-proofs` (which points at rule `test-author` section 5, points 7, 9, 10) and run-wave
`references/post-run.md` Step 7a. Beyond those:
- A single item is its own wave parent.
- Apply each mutation with `references/patch-anchored.py`, `DRY=1` first.
- Mutations on node tests under `claude-plugins/` run in an extraction made by
  `node scripts/redproof-extract.mjs <sha> <outDir> <control test file>...` and count only after
  a green control there (exit 0; record its `control` line on the note). A test under the
  repo-root `scripts/tests/` reads no extracted path: mutate it in the scratch worktree itself,
  after an unmutated control run of the same file there (record pass/tests).
- With no run id, write no meters line and say so in the report.

### Retrospective

The retrospective hook fires on a run's close (mode `extensions.claude-code.retrospective.mode`);
outside a run, run `/session-retrospective` by hand.

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
| work (notes filled, no PR yet) | Step 4c - advance out of work |
| work (review-less, PR open) | front door "Merge gate": wait for `MERGED`, then Final `complete`; Step 4c is not repeated |
| review (no verdict yet) | Step 5 - run review |
| review (verdict passed, PR open) | front door "Merge gate": wait for `MERGED`, then Final `complete`; the review is not run again |
| terminal | Already done — report status |
| an open run found (run-wave preflight F7) | `wave.md`: `/task-orchestrator:run-wave --resume <runId>` |

Steps 2-6 moved out of this file: a citation of "`/implement` Step 5" or "Step 6" written before
the split means the same step in the tier's path file (`wave.md` for Parallel and for any run).
