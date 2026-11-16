# Implement: Delegated path

Read by the orchestrator on one Delegated-tier item (not a batch), once front door Step 1 is done; this file is the whole Delegated path.

One implementer subagent does the work on a working branch, an optional separate test author
writes the tests, and a separate review agent reviews. run-wave does not run a single Delegated
item: every dispatch below is by hand. Front-door sections are cited by name ("Envelope", "Base
freshness", "Verification command", "Planning rules", "Arbitration — Red Author-Authored Tests",
"Merge gate", "After merge", "Retrospective").

---

## Step 2 — Prepare the Branch and Worktree

Run front door "Base freshness" first. The orchestrator creates the branch on the main checkout,
and the subagent works against it (no worktree):

```bash
git checkout -b <branch-name>
```

**If the main checkout is unavailable** (another branch checked out, uncommitted changes present)
**or the orchestrator itself runs from a worktree**, do not touch the occupied checkout. Create a
dedicated worktree instead:

```bash
git worktree add .claude/worktrees/<slug> -b <branch-name> origin/main
```

Work there using absolute paths and `git -C` (never `cd`); after the PR merges, remove the worktree
and delete the branch. Sync local `main` via `git fetch origin main:main` while `main` is not
checked out anywhere, or a normal `git pull` from the main checkout when it is.

Branch names: `fix/<short-description>` for a bug-fix, `fix/<grouped-description>` for a group
of related fixes (front door Step 1, grouping), `chore/<short-description>` for tech debt.

---

## Step 3 — Queue Phase: Planning

Fill the queue-phase notes per schema: `items_read` `context`, and `items_read` `schema` with
`fields: ["guidance"]`, show the expected notes and their guidance (`guidanceKey` is the key of
the first missing required note). `pre-plan-workflow` is optional (use it only if scope needs
exploration); `post-plan-workflow` only if child items need materialization. In collaborative
mode, ask the user to enter plan mode first.

Front door "Planning rules" applies in full: the planning note's `skillPointer` (claims verified in
source), rule `live-checks` (the State matrix) and rule `test-author` section 1 (`test-plan` frozen
before the advance).

Advance: `advance` `transition` with `trigger: "start"`.

---

## Step 4 — Work Phase: Implementation

| Check | When | Who |
|---|---|---|
| Pinned self-check (`ktlintFormat` + compile, `run-profile.json` `compile-self-check`), once before committing | every writer seat | seat, through the helper |
| Own test classes, red then green (`-TestFiles`) | while writing them | test author |
| Changed packages' test classes (`-TestFiles`), at most once | before committing | implementer |
| Full suite + `ktlintCheck` (front door "Verification command") | once, after the last writer commits, before the reviewer | orchestrator |
| Bug-fix revert, coupled-authorship red checks | before merge | orchestrator |
| Budget mutations | after merge (front door "After merge") | orchestrator |
| Reviewer | reads the recorded `Verified:` line; runs nothing | - |

Use `items_read` `schema` (`itemId`) with `fields: ["guidance"]` for the work-phase notes. Follow
`/task-orchestrator:orchestrate` (model table, return formats, UUID inclusion). Delegate to one
implementation subagent, or implement directly; the subagent works on the working branch.

**Test-file ownership boundary.** Rule `test-file-ownership`; the template's `src/test/**` lines
are its item 1.

**Implementer dispatch (by hand).** Paste the `run-profile.json` `verify` entries whose `seats`
list `implementer`, `<worktree>` substituted (per `orchestrate` "Hand-dispatched seats carry the
project's verify commands"):

```
Agent(
  prompt="""
  Item: <full UUID> (<short>). Implement its work phase as its queue notes
  (<task-scope | diagnosis | specification>) describe, and fill the work-phase notes your seat owns.
  Working directory: <absolute path of the checkout>
  Branch (already checked out): <branch-name>
  Scope (modify ONLY these files): <explicit list>
  Do NOT create or modify any file under src/test/**. If your change surfaces a needed test
  update, report it in your return; never edit the test yourself.

  Before committing, run exactly: <the pasted verify command(s)>
  Read the final EXIT=<n> line. EXIT=0: commit. Otherwise fix your files and re-run, at most
  3 runs in total; still red: commit nothing and report the error verbatim.
  Do NOT run :current:test or :current:ktlintCheck; the orchestrator runs the suite.
  Commit as rule `commit-discipline` says (docs_read get, kind rule): by pathspec,
  `git commit --only -- <your paths>`, then verify with `git show --stat HEAD`.
  """,
  description="implementer:<short> [<model>/<effort>]",
  model="<dispatch.model, else the implementation class>",
  effort="<dispatch.effort, else the implementation class>",
  subagent_type="<dispatch.agent when the item's resolved profile names one, else general-purpose>"
)
```

**Model and effort.** Follow `task-orchestrator:orchestrate` "Dispatching an item's phase owner".

**Contract-change sweep discipline (orchestrator-owned, after the implementer returns and before
build verification).** Follow rule `contract-change-sweep`, and declare each repair SHA in the
review handoff.

**SHA capture and disjointness (whenever `needs-test-author` applies).** Capture HEAD before and
after the implementer's dispatch, and again around the test author's (Step 4b), then run rule
`test-file-ownership` item 3 on the two ranges:

```bash
git -C <checkout> diff <pre-sha>..<post-sha> --name-only           # impl range
git -C <checkout> diff <test-pre-sha>..<test-post-sha> --name-only # author range
```

A violation is reverted (`git -C <checkout> revert` the offending commit, or a targeted
`git checkout` of the crossed-boundary file back to the prior SHA) and recorded in the item's
`orchestrator-confirmation` (the orchestrator never writes `implementation-notes`) — do not
silently keep a cross-ownership commit.

---

### Step 4b — Test Authoring

Applies only to items carrying `needs-test-author`. One additional **sequential** dispatch on the
same branch, after the implementer's commit lands and before build verification, so the author
compiles against the real implemented surface. The planning seat already froze `test-plan`; the
test author writes test code and fills `test-manifest`, and never re-opens `test-plan`.

The declarations block is filled first by a separate, non-author `declarations-extractor` seat
(rule `declarations-extractor`: its accuracy contract), and the orchestrator scans it and strips
any implementation prose before the author sees it.

```
Agent(
  prompt="""
  Item: <full UUID> (<short>). You are its independent test author: tests only, src/test/** only.
  Working directory: <absolute path of the checkout>
  Branch (already checked out): <branch-name>
  Fetch these rules first (docs_read get, kind rule, slug the rule name): test-author,
  forbidden-test-patterns, test-assertion-vacuity, test-harness.full-wiring, declarations-extractor.
  Declarations file (the public surface you may compile against): <path>
  Read notes only with a keys filter naming the item's queue-phase keys (items_read schema),
  never the seat's reads_exclude keys, and never open src/main by any tool.
  Before committing, run exactly: <the pasted verify command(s) for seat test-author>
  Run only your own test classes (-TestFiles) to see them red, then green. Never :current:test,
  :current:ktlintCheck or --rerun-tasks.
  Commit as rule `commit-discipline` says, then fill test-manifest per rule test-author section 10.
  Return line: <short>: commit <sha> | files: <list> | scenarios: <covered>/<total> |
  compile: EXIT=<n> [(own-file-errors: <files>)] | missing-declaration: <none or name>
  """,
  description="test-author:<short> [<model>/<effort>]",
  model="<dispatchBySeat test-author model>",
  effort="<dispatchBySeat test-author effort>"
)
```

Capture Test-Pre-SHA and Test-Post-SHA and run the disjointness check (Step 4). A red test the
author wrote goes to front door "Arbitration — Red Author-Authored Tests".

---

### Step 4c — Build Verification and Wrap-Up

**Build verification (orchestrator-owned).** After the last writer seat commits, confirm a clean
tree (`git -C <checkout> status`) and run front door "Verification command" once on the branch's
checkout. A failure means the change broke something: send a fix to a fresh implementer before
continuing, and never retry until green, since a retry hides a real bug. A red author-authored
test is not a build failure (front door "Arbitration — Red Author-Authored Tests").

Then:

1. **Log findings as work items** — any issue surfaced during implementation
   and not addressed here (pre-existing tech debt, optimization opportunities, related bugs) is
   logged via `/task-orchestrator:create-item` before moving on. Do not discard findings.
2. **CHANGELOG bullet**, committed with the change, written here so the reviewer sees it. A
   user-visible change (server behaviour, the MCP or REST surface, config keys, plugin skills or
   hooks) adds ONE bullet under `CHANGELOG.md`'s `## [Unreleased]` section, in its `### Added` /
   `### Changed` / `### Fixed` subsection (plugin skill and hook changes go under `### Plugin`).
   The bullet describes behaviour, not the diff, and carries no volatile counts. A docs-, process-
   or chore-only change adds none; its PR body states `Changelog: none (<why>)`.
   `/prepare-release` Step 8c folds these bullets into the release section.
3. Fill all work-phase notes the orchestrator owns, following their guidance text
   (`items_read` `schema`, `fields: ["guidance"]`), with what downstream agents need to know.
4. **Live-owed check.** Before the work-exit `advance`, apply rule `live-checks` "The Live-owed
   hold".
   - A `live-owed` cell on server behaviour runs `/deploy_to_docker --reuse` from the checkout
     under test, reconnects (`/mcp`), exercises the changed capability through the tools against
     the spec note's acceptance criteria, and records `run <date>: <result>` on its Live-owed
     entry. A change with a migration is never deployed to the shared data volume before merge:
     its entry is `deferred: <holder id>` (rule `live-checks` "Post-merge holder").
5. **Advance out of work** once the agent has returned and the notes are filled:
   `trigger: "start"` when the schema has a review phase, or `trigger: "complete"` when it has none.
   Step 5 reads `newRole`.

---

## Step 5 — Review Phase

- **`newRole` is `terminal`:** the schema has no review phase. Go to Step 6.
- **`newRole` is `review`:** dispatch a **separate** review agent. The agent that implemented the
  code must not review its own work.

**Before dispatching the reviewer**, record the Step 4c build run as the `Verified:` line
(command, result, SHA) in the item's `session-tracking`. The reviewer runs no gradle and reads the
build state from there (rule `closure-notes` item 2).

**Review agent template (copy verbatim, fill placeholders):**

```
You are reviewing MCP item <full UUID> (<short>) on its working branch.

- Checkout: <absolute path of the main checkout or the item's worktree>
- Branch: <branch-name>
- Your review scope: git -C <checkout> diff <base>...HEAD
- Build state: the `Verified:` line of the item's session-tracking note (command, result,
  SHA). Run no gradle.

Run ALL commands from that checkout. For each review-phase note the item's
schema requires, read its `skillPointer` (`items_read` `schema`, `itemId`) and
follow that framework, then fill those notes.

Report every finding at every severity — do not self-filter to a high-severity-only
bar. Mark each finding blocking or observation and state your confidence; the
orchestrator's verdict handling is the downstream filter, not your own judgment.
```

Dispatch it with the Agent `description` `reviewer:<short>`, an explicit `model` and `effort` from
the item's review profile (Step 4 "Model and effort"), and `subagent_type` its `dispatch.agent`.

**Handling the verdict:**

| Verdict | Action |
|---------|--------|
| **Pass** | Proceed to Step 6 |
| **Pass with observations** | Dispose each observation (below), then proceed to Step 6 |
| **Fail — blocking issues** | Stop and report to the user with the full findings. Do not attempt to fix autonomously — bring the human into the loop. |

**Dispositions.** Before advancing, write the item's `orchestrator-confirmation` per its guidance
and run-wave `references/post-run.md` Step 5 "Observation triage"; a `fixed` finding goes to a
fresh implementer or test author scoped to the finding ids.

Review failures surface issues that may indicate systemic problems worth learning
from. Automatically retrying hides these signals.

---

## Step 6 — Finalize and PR

0. **Post-dispatch commit audit (non-blocking):** run `git log --oneline -3` and
   `git status --short` and compare against what the orchestrator itself committed. Any commit a
   subagent made despite stop-boundary instructions is flagged here — inspect its scope before it
   rides into the squash-merge (a subagent commit lacks the co-author trailer; reconcile at merge
   time). On items carrying `needs-test-author`, check each commit's file list for
   cross-ownership: an implementer commit touching `src/test/**`, or a test-author commit touching
   `src/main/**`, is flagged even if it slipped past the Step 4 disjointness check.
1. Confirm the branch is committed (the subagent committed; anything the orchestrator adds uses
   front door "Merge gate" Commit form), then run its CHANGELOG check and Docs line.
2. Push and open the PR:
   ```bash
   git push -u origin <branch-name>
   gh pr create --base main --title "<type>(<scope>): <description>" --body "$(cat <<'EOF'
   ## Summary
   <2-4 bullets>

   ## Test Results
   <test count, pass/fail, new tests>

   ## Review
   <verdict summary>

   ## Docs
   <every changed doc path from `git diff --name-only <base>...HEAD`>

   ## Changelog
   <the [Unreleased] bullet text, or none (<why>)>

   ## MCP
   <item ID>
   EOF
   )"
   ```
3. Run front door "Merge gate" (the gate), then its cleanup.
4. Advance the item to terminal only if it is not already terminal; otherwise skip:
   ```to-call
   {"tool":"advance","op":"transition","args":{"itemId":"4b7d0e19","trigger":"complete"}}
   ```
   An item with children: run front door "After merge" (post-merge live acceptance) before this
   advance.
5. Front door "After merge" (red-proofs, with the item as the wave parent), then
   "Retrospective".

Report the PR URL and a summary.
