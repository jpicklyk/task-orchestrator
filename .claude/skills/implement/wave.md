# Implement: wave path

Read by the orchestrator on a Parallel-tier feature or a Delegated batch of 2+ ready items, and by a coordinator handed a run-wave review, once front door Step 1 is done; this file is the whole wave path.

A wave runs through `/task-orchestrator:run-wave` (Step 3b), which generates every seat prompt.
Two modes: **shared** (a Parallel feature: one planned scope, its waves run in turn in one feature
worktree, one PR when the parent is terminal) and **per-item** (a Delegated batch of unrelated items: one branch and PR per
item). Front-door sections are cited by name ("Envelope", "Base freshness", "Verification
command", "CHANGELOG bullet", "Live-owed check", "Planning rules", "Arbitration — Red Author-Authored Tests", "Merge gate", "After
merge", "Retrospective"). A dispatch run-wave does not generate (no run-wave, a one-off fix agent,
an arbitration re-dispatch) follows the front door Route table's hand-dispatch row.

---

## Step 2 — Prepare the Branch and Worktree

Run front door "Base freshness" first.

**Shared mode:** create a **single feature worktree** that all child agents share:

```bash
FEATURE_SLUG=<slug>
FEATURE_BRANCH=feat/$FEATURE_SLUG
FEATURE_WORKTREE=.claude/worktrees/feat-$FEATURE_SLUG

# Resume detection — if the branch/worktree already exist (orchestrator restart
# mid-feature), reuse them rather than recreating:
if git show-ref --verify --quiet "refs/heads/$FEATURE_BRANCH"; then
  echo "Resuming existing feature branch $FEATURE_BRANCH"
else
  git branch "$FEATURE_BRANCH" main
fi
if [ ! -d "$FEATURE_WORKTREE" ]; then
  git worktree add "$FEATURE_WORKTREE" "$FEATURE_BRANCH"
fi
```

All child-task agents work in this **shared** worktree (Step 4). The feature branch is pushed and
PR'd **once**, across every wave, when the parent feature reaches terminal (Step 6); no PR opens between waves. One worktree per feature, not per
child: one commit history, one CI cycle, one PR, and no cross-PR contamination. The legacy
per-dispatch `isolation: "worktree"` pattern is deprecated.

**Plan file as dispatch contract (every shared wave).** Before the first dispatch, instantiate
[`references/dispatch-contract-template.md`](references/dispatch-contract-template.md) at
`<main-checkout>\plans\<slug>.md` and fill every slot (the template's `n/a` form for one that does not apply).
`plans/` is gitignored and absent from the worktree, so always hand out the absolute path recorded
in the Header's Contract path line. Prompts point at a slot and never paraphrase it: agents read
only their prompt and their schema guidance, so a rule left in skill prose never reaches them.
Under run-wave, seat prompts are generated and do not read the file; it then serves the
orchestrator (fallback dispatches, the commit map) and the reviewers. The contract makes execution
free of deviation, not the plan correct: front door "Planning rules" covers that.

**Per-item mode:** run-wave creates one worktree and branch per item (`run-profile.json`
`branchPrefix.perItem`, `fix/`); on resume it reuses an existing branch or worktree.

Branch names: `feat/<feature-slug>` for a feature-implementation parent (the integration branch),
`fix/<short-description>` per item in per-item mode.

---

## Step 3 — Queue Phase: Planning

Full planning pipeline. The two interaction modes (front door Step 1) differ only in who enters
plan mode:

1. **Collaborative:** ask the user to enter plan mode; the `pre-plan-workflow` and
   `post-plan-workflow` hooks fire on plan mode entry and exit (context gathering,
   materialization). **Autonomous:** read and follow the `pre-plan-workflow` skill yourself
   (existing MCP state, schema requirements, the definition floor), then research the codebase.
2. Fill every queue-phase note following its guidance text (`items_read` `schema`,
   `fields: ["guidance"]`) and its `skillPointer`, in either mode: a pointer that is a served rule
   key is fetched as a rule, any other pointer is invoked as a skill (`schema-workflow`).
   Front door "Planning rules" applies in full.
3. After plan approval, materialize child items if the plan calls for them (`post-plan-workflow`:
   the exit hook in collaborative mode, by hand in autonomous mode).
4. Advance the item: `advance` `transition` with `trigger: "start"`.

**Planning seat (one `planning`-class agent per stream, after materialization and before any
implementer).** Under run-wave this is the `planner-v1` stage; by hand, dispatch it as
[hand-dispatch.md](hand-dispatch.md) says.

---

## Step 3b - Run the wave

Run `/task-orchestrator:run-wave` over the parent (shared) or the batch's item ids (per-item), one
run per topological layer:

- **Shared:** `--mode shared --worktree $FEATURE_WORKTREE --branch $FEATURE_BRANCH` reuses the
  Step 2 worktree and branch.
- **Per-item:** `--mode per-item`.

The run plan dispatches the planner-v1, implementer, declarations-extractor and test-author seats
(Steps 3-4b describe what they do), then runs post-run: verify (ownership, SHAs, the pre-merge
red-proofs), the notes audit and the batched advance. This repo's `run-profile.json` sets `review` to
`{mode: "handoff", skill: "implement", step: "wave.md Step 5", onFail: "stop"}` (`onFail` `stop`: a review
Fail waits for the user, as Step 5 below already does): the run stays open, a reviewer seat is assigned per item, and control comes
back to Step 5 here. The run closes after Step 5's verdicts (`post-run.md` Step 5, then Step 7).
A run already open on the root is resumed instead (`--resume <runId>`, run-wave preflight F7).

---

## Step 4 — Work Phase: Implementation

Who runs gradle, and when: rule `test-author` section 5 points 10-11; the table keeps this repo's mechanics.

| Check | When | Who |
|---|---|---|
| Pinned self-check (`ktlintFormat` + compile), once before committing | every writer seat | seat, through the helper |
| Own test classes, red then green (`-TestFiles`) | while writing them | test author |
| Changed packages' test classes (`-TestFiles`), at most once | before committing | implementer |
| Orchestrator `verify` entries (front door "Verification command"), incremental | once per wave, after the last writer commits, before any PR | orchestrator |
| `--rerun-tasks` | only the run after a dependency or Gradle upgrade | orchestrator |
| Bug-fix revert, coupled-authorship red checks | per item, before merge | orchestrator |
| Budget mutations | after merge (front door "After merge") | orchestrator |
| Reviewer | reads the recorded build state; runs nothing (a doubted red-proof is re-run scoped) | — |

A seat prompt never carries a suite command: it points at the contract's Compile self-check slot.

**Dispatch.** Under run-wave both cases below are executed by the run plan (Method A via
`Workflow`, Method B via `next`-scheduling), reading each stage's `dispatchBySeat` entry for model,
effort and agent. By hand:

- **Independent children:** dispatch parallel subagents into the **shared feature worktree**.
  Each agent receives the worktree path and branch name. Do **not** use `isolation: "worktree"` on
  the Agent tool — that would spawn a separate worktree per dispatch.
- **Dependent children** (a semantic dependency; sharing a file alone is not one): dispatch
  sequentially into the shared worktree, waiting for each agent's commit before the next.
- **Shared code first.** When a child modifies shared code (domain models, enums, database
  schema, test infrastructure), dispatch it first, run Step 4c's build verification on its commit
  to set a clean baseline, then dispatch the rest in parallel. Several agents reporting the same
  "pre-existing" failures is contamination from an earlier parallel commit, not a baseline.

**File-edit overlap discipline:** Scope each agent to its row in the dispatch contract's File
ownership slot (filled at Step 2 from the dispatch contract template). When inherent overlap exists, under
run-wave declare the file in both scopes (`Shared file, lock-serialized: <path> (with <other short>)`)
and add no BLOCKS edge; a hand-dispatch still dispatches sequentially, without an edge.

**Test-file ownership boundary.** Rule `test-file-ownership`.

**Contract-change sweep discipline (orchestrator-owned, after the implementers return and before
build verification).** Follow rule `contract-change-sweep`, and declare each repair SHA in the
review handoff (the Review scoping commit map). When a fix changes shipped behaviour after docs were written, grep the changed docs for claims
about the old behaviour and fix or route each hit (dispositions as rule `planning-sweeps` point 3).

**Model and effort.** Follow `task-orchestrator:orchestrate` "Dispatching an item's phase owner".

**Ownership.** Under run-wave, `post-run.md` Step 1 matches each item's commits to its owned files.
By hand, capture each agent's pre/post SHAs and run the disjointness check (front door Route
table, hand-dispatch row).

---

### Step 4b — Test Authoring

Applies only to items carrying `needs-test-author`: one test-author agent **per child**, run as a
dedicated wave between the implementation wave and Step 4c, so each author compiles against the
real implemented surface. The planning seat already froze `test-plan`; the author writes test code
and fills `test-manifest`. Under run-wave the `declarations-extractor` and test-author seats are
run-plan stages, and the orchestrator's scan of the declarations block for implementation prose is
the `scan-declarations` step (Method B) or the run script's `scanDeclarations` call (Method A). A
red test the author wrote goes to front door "Arbitration — Red Author-Authored Tests".

---

### Step 4c — Build Verification and Wrap-Up

**Build verification (orchestrator-owned, serialized).** After the last writer seat of a
parallel batch commits (or between sequential children), confirm a clean tree
(`git -C <feature-worktree> status`) and run front door "Verification command" with `<wt>` the
feature worktree (per-item mode: each item's worktree). This is the wave's ONE full-suite run:
incremental, before any PR opens; under run-wave, before `post-run.md` Step 4's batched advance.
Do not re-run it per seat return, and do not let a seat run it for you.

A failure means a recently-committed child broke something. Dispatch a fix agent
(same shared worktree) before continuing. Do **not** advance any child to review
until the build is green, and never retry until green: a retry hides a real bug. A red
author-authored test is not a build failure (front door "Arbitration — Red Author-Authored Tests").

**Post-implementation steps** (in the feature worktree):

1. **Log findings as work items** — any issue surfaced during implementation and
   not addressed here is logged via `/task-orchestrator:create-item` before moving on.
2. **CHANGELOG bullet** - front door "CHANGELOG bullet"; written here, in the work phase, so the
   reviewer sees it, once, by the orchestrator or the docs seat after the implementation wave and
   before review - never an implementer (the dispatch contract's **Docs** slot).
3. Fill all work-phase notes the orchestrator owns, following their guidance text
   (`items_read` `schema`, `fields: ["guidance"]`).
4. **Live-owed check** - front door "Live-owed check".
5. Advance each child out of work: `trigger: "start"` when its schema has a review phase, or
   `trigger: "complete"` when it has none (under run-wave this is `post-run.md` Step 4's batched
   advance). Step 5 reads `newRole`.

---

## Step 5 — Review Phase

A coordinator handed the review by run-wave (`review: "handoff"`) starts here, with the run open
and a reviewer seat assigned per item (`post-run.md` Step 5).

- **`newRole` is `terminal`:** the schema has no review phase (its resolved schema has no
  review-role note). Go to Step 6.
- **`newRole` is `review`:** dispatch a **separate** review agent; the agent that implemented the
  code must not review its own work.

**Reviewer scoping.** Per-child reviewers remain the default for code-bearing
waves. When every child in the wave is content-only (config, skill, or doc edits — no `src/main`
or `src/test` changes) AND the wave shares a pinned plan-file contract (Step 2), dispatch ONE
consolidated reviewer (model and effort from the review seat's `dispatchBySeat`) over the full feature-branch diff (`git diff main...<FEATURE_BRANCH>`)
instead of N per-child reviewers. Cross-file coherence defects — sibling skills stating
contradictory rules, gate placement contradicting seat timing, an example contradicting its own
rule — are visible only to a reviewer holding the whole diff; per-child reviewers structurally
cannot see them, and a shared naming contract does not prevent them. The consolidated reviewer
fills each reviewed child's review-phase notes (all under the same reviewer actor). When the
children's schema has no review phase, this Step 5 consolidated pass is skipped. Either way the parent feature's
`review-checklist` is never filled here: the parent is reviewed once, in Step 6, "When all children reach terminal", item 3 (`run-wave`
`post-run.md` Step 7b), after every child is terminal.

A per-child reviewer reads **that child's owned files** in the shared worktree, per rule
`review-scoping` point 1 and the contract's **Review scoping** slot.

**Review agent template (copy verbatim, fill placeholders):**

```
You are reviewing one child task within a shared feature worktree.

- Feature worktree: <FEATURE_WORKTREE_PATH>
- Feature branch: <FEATURE_BRANCH>
- Dispatch contract: <ABSOLUTE path from its Header "Contract path" line, e.g.
  D:\Projects\task-orchestrator\plans\<slug>.md> — read it first; it wins on conflict
  with this prompt. It lives in the main checkout, not in the worktree above.
- This child's owned files (its row in the contract's File ownership slot):
  <FILE LIST>
- Your review scope is the diff of exactly those files:
  git -C <FEATURE_WORKTREE_PATH> diff <BASE_SHA>..HEAD -- <FILE LIST>
- Other children committed into this same branch, and later fix-up, formatter or
  fixture-repair commits may touch these files. Review the owned-file diff above as it
  stands now; do NOT review another child's files, and do NOT bound your review by a
  commit range.

Run ALL commands from within the feature worktree.
Read ALL files from that directory. Do NOT read from the main working directory.

Tests have already been verified green by the orchestrator after the most recent
commit batch. Run no gradle — the contract's Compile self-check slot states who runs
it, and the Review scoping slot records the build state you rely on.

Before your first review-note write, load the reviewer seat's skills as `items_read`
`schema` gives them (`seats[]`), or, when it declares none, the plugin defaults its hook
names on your first `items_read` `context` read of the item.
Fetch rule `protocol.read-only-agent`, plus the reviewer seat's `rules` from `items_read`
`schema` (`seats[]`), or `review-scoping` and `review-cited-claims` when it has no `rules`
field, with `config` op `rules`, and follow them. For each
review-phase note the item's schema requires, read its `skillPointer` (`items_read`
`schema`, `itemId`): a pointer that is a served rule key is fetched as a rule (`config`
op `rules`), any other pointer is invoked as a skill. Follow it, then fill those notes.

Report every finding at every severity — do not self-filter to a high-severity-only
bar. Mark each finding blocking or observation and state your confidence; the
orchestrator's verdict handling is the downstream filter, not your own judgment.
```

In per-item mode, the same template names the item's own worktree and branch, and the scope is
`git -C <item worktree> diff <BASE_SHA>..HEAD -- <owned files>`.

**Label and seat parts.** Dispatch every reviewer with the Agent `description` `reviewer:<short>` (the label
table is the "Label rule" of run-wave `post-run.md`; do not restate it). On a run-wave run, which is open under
`review: "handoff"` with a reviewer seat assigned per item (`post-run.md` Step 5), append to the template the SEAT
TOKEN and END SEAT parts that `node "<helper>" review-prompt --plan <plan> --item <short> --result <result>` renders for
a bound run: copy those two paragraphs from its output and never write the token rules by hand. The consolidated
reviewer gets each child's reviewer token file (`tokens/<short>.reviewer.token`), passes the matching child's token on
that child's calls, and ends each child's seat. A consolidated reviewer is one Agent, labelled `reviewer:<short>` with
the short id of the first child in plan order: `provenance` looks a seat's meters up as `<seat>:<item short>`, so
`--extra-seats reviewer:<model>` goes on that first child only, and every other child's `delegation-metadata` carries,
from line 3 on, a prose pointer to that child instead. The session coverage counts the one label once.

**Handling the verdict:**

| Verdict | Action |
|---------|--------|
| **Pass** | Proceed to Step 6 |
| **Pass with observations** | Proceed to Step 6; dispose observations per Dispositions at child close (`fixed` only under run-wave `post-run.md` Step 5 "Observation triage") |
| **Fail — blocking issues** | Stop and report to the user with the full findings. Do not attempt to fix autonomously — bring the human into the loop. |

A child's review failure does not stop its siblings (their commits are already on the feature
branch): continue them and report every failure together. Each failed child then blocks parent
finalization (fix and re-review), is descoped (cancelled), or has its commits reverted.

Once the user rules a fix on a run-wave item under `review: "handoff"`, the fix cycle takes the still-open-run branch of run-wave `post-run.md` Step 5 "Fallback" (re-assign the base seat, reseat with the run's own `plan.json`, and after the re-review rewrite `session-tracking` and re-upsert `delegation-metadata` as that Fallback's step 5 says, under the run's coordinator token); it opens no `<runId>-a<n>` run, and the numbered steps 1-6 apply only once the run has closed.

**Dispositions at child close.** When a child's review returns Pass or Pass with observations,
write the child's `orchestrator-confirmation` before advancing it, per its guidance and run-wave
`post-run.md` Step 5 "Observation triage"; a `fixed` finding goes to one `implementer-a<n>` (or
`test-author-a<n>`) seat rendered with `reseat --cause review --findings <ids>` and scoped to the
finding ids.

Review failures surface issues that may indicate systemic problems worth learning
from. Automatically retrying hides these signals.

---

## Step 6 — Finalize and PR

### Shared mode - finalize ONCE at parent-feature completion

**For each child task** (after its review passes, if it has one):

1. For children whose schema/trait declares a review phase (the schema requires a
   `review-checklist` note), confirm `review-checklist` is filled. Children without one advance work→terminal
   directly — there is nothing to confirm.
2. Advance the child to terminal only if it is not already terminal (Step 4c item 5 already
   advanced each child out of work; a review-less child is terminal by now, so skip it). A child
   sitting in review takes one `advance` `transition` with `trigger: "complete"` (`itemId` the
   child's id) to reach terminal, and only after its review passes. While the run is open (under
   `review: "handoff"` it stays open through the review), that advance and the child's
   `orchestrator-confirmation` go under the coordinator token: a token-less write to an orchestrator-seat
   key of a run member is refused (`post-run.md` Step 3), and the coordinator token passes the seat
   gate (`current/docs/api-reference.md`, `advance`, `seat_forbidden`). Then close the run with `post-run.md` Step 7.
3. **Do NOT push. Do NOT create a PR.** The work is committed to `feat/<feature-slug>`
   inside the shared worktree; that's the integration point.

**When children remain for a later wave**, the wave ends with its Step 4c run green and its
commits on the branch. Run front door "Base freshness" for the branch: when origin/main moved,
`git -C <feature-worktree> merge origin/main -m "chore: merge main into feat/<feature-slug> [<parent-short>]"`,
never rebase (the tagged conventional subject keeps the branch eligible for a merge commit, front
door "Merge method"). Then Step 3b for the next layer.

**When all children reach terminal**, the parent feature is ready to finalize (a child that is only a live
check needing a refreshed plugin cache or a user opt-in counts as done here; it is split off after the
merge, step 8):

1. Fill the parent's `implementation-notes` and `session-tracking` per each note's guidance and
   rule `closure-notes` point 5 - under the coordinator token while the parent is a member of an
   open run; the only re-edits are the ones run-wave `references/post-run.md` Step 3 sanctions, and
   the step 8 post-merge live acceptance line is written as rule `live-checks` "Post-merge holder" (d) says.
2. **No second suite run.** The last wave's Step 4c run is the whole-suite run, after the last writer
   commit. If a writer seat committed after it (a fix seat, a merge round), that run was not the
   last writer's, so Step 4c runs again before the PR.
3. Advance the parent to review. The orchestrator never authors the parent's `review-checklist`,
   whatever the child count. With 2+ children, run the feature review of `run-wave`
   `post-run.md` Step 7b: one independent `task-orchestrator:reviewer` (explicit `model`, Agent `description`
   `reviewer:<parent-short>`; it runs under an assigned reviewer seat only when the parent is a member of the
   open run, otherwise token-less) over
   `git -C <feature-worktree-path> diff <base>...HEAD`, handed each child's verdict and
   disposition count lines (a child with no review phase is listed as such). The reviewer
   writes only feature-level findings, never re-lists a child's disposed observation, and fills
   the parent's `review-checklist`. The orchestrator records one line per finding in the
   parent's `orchestrator-confirmation` (`fixed <sha|PR> | follow-up <item-id> |
   accepted: <reason>`, ending `N of N disposed`). With exactly one child, Step 7b
   does not apply (it covers 2+ children only), but the rule is the same: the parent review is
   still done by an independent `task-orchestrator:reviewer` (explicit `model`) over the feature
   range, never by the orchestrator, handed the child's verdict and disposition count lines (or
   listed as review-less), and the dispositions go in the parent's `orchestrator-confirmation`.
   See Step 5's reviewer scoping, which never fills the parent note.
4. **CHANGELOG check and Docs line** - as front door "Merge gate" says: the bullet the
   orchestrator or docs seat wrote before review (Step 4c), or `Changelog: none (<why>)` in the PR
   body; the Docs line and the `CHANGELOG.md` requirement are built from
   `git diff --name-only <base>...HEAD`, never from the plan, and recomputed after any merge round.
5. Push the feature branch:
   ```bash
   git -C <feature-worktree-path> push -u origin feat/<feature-slug>
   ```
6. Create **one** PR for the whole feature:
   ```bash
   gh pr create --base main --title "feat(<scope>): <feature description>" --body "$(cat <<'EOF'
   ## Summary
   <feature-level summary aggregating all children>

   ## Children completed
   - <child-1 title> (<MCP UUID>)
   - <child-2 title> (<MCP UUID>)
   ...

   ## Test Results
   <total test count, new tests added across feature>

   ## Review
   <feature reviewer verdict; N of N dispositions>

   ## Docs
   <every changed doc path from `git diff --name-only <base>...HEAD`>

   ## Changelog
   <the [Unreleased] bullet text, or none (<why>)>

   ## MCP
   Parent: <parent UUID>
   Children: <list of child UUIDs>
   EOF
   )"
   ```
7. **Merge gate** - run front door "Merge gate" (merge method, green checks, fallback merge,
   state `MERGED` confirmed) first. The method is `--merge` when every first-parent commit subject
   is conventional and item-tagged, else `--squash`; the branch is never rebased to qualify. Only after the PR is `MERGED`:
   ```bash
   git checkout main
   git pull origin main
   git worktree remove <feature-worktree-path>
   git branch -D feat/<feature-slug>
   ```
8. **Post-merge live acceptance** - front door "After merge", BEFORE step 9: a terminal parent
   takes no new children.
9. Advance the parent feature to terminal (only after `N of N disposed`) with `trigger: "complete"`;
   when step 8 applies this is its (e), run once after (a)-(d).
10. **Wave-boundary red-proofs** - front door "After merge", every child's budget mutations in ONE
   batch with the parent as the wave parent (runner `red-proofs:<parent-short>`). Background; it
   gates nothing.
11. **Retrospective** - front door "Retrospective".

### Per-item mode - one PR per item

For each item, once its review passes, or once `post-run.md` Step 4 has completed it (a
review-less item): its own PR from its own branch, per front door "Merge gate" (Commit form,
CHANGELOG check and Docs line, the gate), with a single-item PR body (Summary, Test Results,
Review, Docs, Changelog, MCP item id). While the run is open, the item's
`orchestrator-confirmation` goes under the coordinator token. A reviewed item's advance to
terminal (`trigger: "complete"`) follows its PR's `MERGED` (front door "Merge gate", Final
`complete`); a review-less item is already terminal and takes no further advance. That advance runs
under the coordinator token while the run is open; a token-less write to an orchestrator-seat key of a run member is refused
(`post-run.md` Step 3). When every item has its verdict, close the run with `post-run.md` Step 7. A
parent with 2+ children and a review phase gets `post-run.md` Step 7b's feature closure once its
last child's PR has merged. Then front door "After merge" (one red-proof batch for the wave
parent) and "Retrospective".

### Hand-off

A hand-off to a later wave or session is a `docs_write` `page` document: merged PRs, what is
unblocked, owed cells, and any file or line inventories. No note key holds it.
