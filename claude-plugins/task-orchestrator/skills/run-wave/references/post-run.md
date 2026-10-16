# Post-run protocol

Runs once a run's execution phase is done — either the Method A task notification arrives, or
Method B's own loop reaches `next → {complete: true}`. This is the same sequence for both
methods; the only difference is what triggers entry into it (see the main `SKILL.md` Step 7).

Each step below writes `state.phase` before it ends, per the turn-boundary protocol in
`SKILL.md` — resume (F7 / the Resume matrix) re-enters at the first step not yet recorded.

---

## Step 0 — Load the result

Re-read `run/<runId>` (the plan document) and its `state` document. Save the notification's
result JSON (Method A) or the accumulated `outs`/`stages` from state (Method B) to
`<scratchpad>/run-wave/<runId>/result.json`. Any item with no envelope recorded at all — neither
a successful `stage-result` nor a `stopped` marker — is treated as `stopped: "agent returned
null"`. Set `state.phase = "notified"`.

Tool: `manage_plan_documents(operation="get", ...)`.

---

## Step 1 — Verify

`node "<helper>" verify --plan <scratchpad>/run-wave/<runId>/plan.json --result <scratchpad>/run-wave/<runId>/result.json` — **exits 0 when every item is `ok`, 3 when any item is not**
(the CLI gathers `gitFacts` itself: `git cat-file -e` per declared commit SHA, plus a `git log`
walk per distinct worktree named in the plan). Read the JSON body regardless of exit code — a
non-zero exit here is an expected result, not a call to retry. Per item this checks: the declared
commit SHAs actually exist, each stage's committed file list is a subset of what that seat was
allowed to touch (implementer ⊆ `mainFiles ∪ docFiles`; test-author ⊆ `testFiles ∪
existingTestEdits[].file`; never cross-owned), commit subjects carry the `[<short>]` tag and a
`Seat:` trailer, and it returns `items[].redProof.shape` - the red-proof budget the planner
derived for that item (`null` when the item had no planner-v1 output to derive one from). An
untagged commit (no `[<short>]` in its subject) surfaces as a top-level `warnings` entry, not a
per-item finding, and does not by itself fail the item.

**How ownership is matched.** Declared files and changed paths are both normalized to
repo-relative POSIX before comparison: `\` becomes `/`, empty segments (`//` runs, a leading or
trailing `/` after an absolute prefix) and `.` segments are dropped, `..` segments are kept as
written (never resolved), a drive letter is lowercased, and a leading root prefix is stripped
(case-insensitively for drive-letter paths). So `src/./x.js`, `src//x.js` and
`<worktree>/src/x.js` are all `src/x.js`, and `.`, `./` and a path equal to the worktree or repo
root are `''` (the whole tree); the filesystem roots `/` and `x:/` stay absolute. The
roots come from git per worktree (`git rev-parse --show-toplevel`, plus the parent of
`--git-common-dir` for the main checkout); with no roots available, the item's `worktree` is the
only root. An absolute path outside every root never matches and is reported. A declared
whole-tree entry (`.`, `./`, the worktree itself) owns nothing in verification, so every file its
seat writes is reported `unowned` — declare real paths. A declared
directory (with or without a trailing `/`) owns every file beneath it, new or modified, and a
declared entry containing `*` or `?` is a glob (`**` spans segments, `*` stays within one,
`?` is one non-`/` character) — this applies to `testFiles` globs too. Only `*` and `?` are
wildcards: `[`, `]`, `{` and `}` are literal characters everywhere (ownership, lock keys and the
review pathspec), so `src/[ab].js` names one file called `[ab].js`, not `a.js` or `b.js`. **Cross-item precedence:**
if a file is exactly declared (non-glob) by another item in the run and not exactly declared by
the writer, it is reported as `implementer wrote <file> owned by item <short>` (or
`test-author wrote ...`) even when the writer's own directory or glob would cover it. When the
writer covers a file only through a directory or glob and another item's directory or glob covers
it too, it is reported as `implementer wrote <file> also covered by item <short>`; two items that
both declare the same file exactly are not flagged (their writes are serialized by locks). A file
outside everything the writer declared stays `implementer wrote unowned <file>`.

**Lock keys and the review diff use the same normalization.** In shared mode a writer stage locks
`file:<repo-relative path>` per declared entry, relativized against the item worktree and then
`args.repoRoot` (the main checkout, filled from the snapshot's `git.repoRoot`). Locks are
conservative: a directory or glob contends with every path under its wildcard-free base
(case-insensitive), so `src/*.js` waits on `src/x.js`; every spelling of one path gets the same
key, so `src//x.js` waits on `src/x.js`. An entry outside both roots, one containing `..`, one
that names the whole tree (`.`, `./`, the worktree or repo root), or an empty declaration locks
the whole worktree (`worktree:<path>`); `extraLockKeys` need an exact match. In `per-item`
worktree mode the planner's declared files decide overlap deferral instead of locks: an entry
outside both roots, one containing `..`, or a whole-tree entry claims the whole tree and overlaps
every entry of the other item, but an item that declares no files at all claims nothing (a
deferral stops the lower-priority item for this run, which is heavier than a lock wait). The
exception is `appendOnlyFiles` (profile globs): a pair of claims that are equal (case-insensitive),
glob-free, not whole-tree, and match one of those patterns is a merge-time conflict, not an overlap,
so it never defers; any other conflicting pair still does. The reason names the files:
`overlap <short> on <f1>, <f2>` (an unrooted claim prints as `**`). The reviewer's `git diff` pathspec covers mainFiles, docFiles, testFiles and
existingTestEdits, each double-quoted with `:(literal)` or (for a glob) `:(glob)` magic; entries
outside both roots are named on an `UNROOTED (not in pathspec): ...` line instead, and when any
entry names the whole tree the diff has no pathspec at all.

**Symbolic refs.** A stage `commits.pre`/`post` that is not a 7-40 character lowercase hex SHA
(for example `HEAD`) is resolved in the item's worktree with `git rev-parse --verify
<ref>^{commit}` rather than `cat-file`. Each item row carries `resolvedRefs: [{seat, field, ref,
sha}]` recording the SHA. An unresolvable ref fails the item with `unresolved ref <ref> (<seat>
<field>)`, and a resolved `post` that is not one of that item's `[<short>]`-tagged commits fails with
`ref <ref> resolved to <sha7>, not a commit of this item` (for instance HEAD in a shared worktree
now pointing at another item's commit).

**Red-proofs themselves are run by you, the orchestrator**, not the helper — `verify` only
returns the budget (`items[].redProof.shape`, plus `items[].redProof.commands` for whichever
of the project's `run-profile.json` -> `verify[]` entries name the `orchestrator` seat); this
repo's profile names the lock helper self-check and a scratch-worktree revert-and-rebuild.

**The red-proof policy is a targeted budget, not one proof per scenario, and it runs in two
places.** The full statement lives in the served `test-author` rule, section 5. What you run
HERE, per item, before the item advances or merges:

- **Bug fix:** one revert-the-fix run on the regression test (red before, green after), scoped
  to that test class.
- **Coupled authorship** (the author read the implementation, or one agent wrote both): the red
  check on every scenario asserting new behaviour.
- **Skip** test-only items, pure refactors and package moves, migrations, and glue, config,
  wiring, logging or docs.
- **Record per item** in the Review scoping slot: proofs run, gaps found, approximate minutes.
  Reviewers read these results and do not re-run them, except to verify one they doubt, and then
  scoped (`-TestFiles`), never as the whole suite.

What you do NOT run here: the decision-point **budget mutations** (up to about 5 per item for
new guard, gate, transition, classification, boundary or security behaviour; concurrency and
atomicity faults). Those are a test-quality audit, not a defect finder (phase 1 of 4.0: about
228 runs for 8 gaps; phase 2: 3 gaps, while the blind tests themselves found the real defects),
so they run after the wave's PRs merge, batched across the wave, at the merged SHA — Step 7a
below. Carry each item's `redProof.shape` forward to that step unchanged.

A `verify` failure (`items[].ok: false`, non-empty `findings`) on any item
means that item **fails this run** — do not advance it in Step 4 below, and surface the specific
failure (missing SHA, foreign file, missing trailer, failed red-proof) rather than a generic
"verify failed".

**Applying a mutation or a narrowest revert in the scratch copy** is a text edit you must be able
to trust, so never do it with a shell one-liner (`sed -i`, `node -e`, a heredoc-built script):
those lose backslashes in the Bash tool and miss anchors in a CRLF checkout, and a missed anchor
leaves the file unchanged, so the red-proof is wasted or falsely green. Write the edit spec or script with
the Write tool into the scratchpad, normalize line endings before matching, require each anchor
to match exactly once, dry-run first, and confirm the diff in the scratch copy before building.
When the project ships an anchored-patch helper (this repo:
`.claude/skills/implement/references/patch-anchored.py`, `DRY=1` first), use it instead of a
hand-written script.

---

## Step 2 — Actor audit

`query_notes(operation="list", itemId=<item>, keys=[<that item's seat notes>], includeBody=false)`
for every item in the run; write the observed `{itemId, key, actorId}` rows to
`<scratchpad>/run-wave/<runId>/observed-actors.json`. Then run `node "<helper>" actors --plan
<scratchpad>/run-wave/<runId>/plan.json --notes <scratchpad>/run-wave/<runId>/observed-actors.json
--result <scratchpad>/run-wave/<runId>/result.json`
— passing `--notes` is what turns this call from *listing* the expected table (`{itemId, key,
actorId}`, `actorId` = `<seat>:<short>:<runId>`) into *auditing* observed against expected.
**Always pass `--result`**: it limits the expected notes per item to stages whose result status
is actually `done`, so an item whose stages were deferred or never ran is reported `skipped: true`
(not a `missing`-notes failure) instead of flagging notes from a stage that never executed. It
**exits 0 when every item's notes match (or is `skipped`) and 3 when any item has a `missing` or
`mismatched` entry**; as with `verify`, read the JSON body regardless of exit code.

An item carrying any `missing` or `mismatched` entry means that item **fails this run**, same as
a Step 1 verify failure — do not advance it. The rule this enforces: only the seat that owns a
note may (re-)write it; a mismatch is evidence something wrote outside its lane, not a cosmetic
discrepancy to wave through.

An **optional** note (a stage's `optionalNotes`; the expected-actors table marks it `optional:
true`) that was never written is **not** `missing` and does not fail the item — but if it was
written under another actor it is still `mismatched`. In a seat-less plan `delegation-metadata` is not a seat's
note: the planner lists it under the item's `orchestratorNotes`, no seat
prompt may write it, and the orchestrator records it in Step 3.

---

## Step 3 — Orchestrator notes

Two notes, both actor `{id: "orchestrator:<session>", kind: "orchestrator"}` (not any seat's
actor — these are the front door's own notes about the run, filled after Steps 1–2 have
concrete results to report):

1. **`session-tracking`** — outcome per item, files touched, deviations from the plan, and
   Friction (the declarations-scan hit count from Method B step 4, any Step 0 fallback taken in
   `SKILL.md`, any `verify`/actor-audit failure). Write this **after** verify and the actor audit
   have run, in confirmed past tense — not as a speculative "pending verification" draft you
   intend to edit later.
2. **`delegation-metadata`** — the single provenance line, produced by
   `node "<helper>" provenance --plan <plan> --result <result> --method A|B --turns <n> --usage <usage-file> [--meta-dir <dir>]`. **Method A: `--usage` is mandatory.**
   Before running the helper, write the Workflow task notification's usage (total tokens, duration in
   ms) to `<scratchpad>/run-wave/<runId>/usage.json` as `{"tokens": N, "duration": N}`; the helper
   records that run total once, on the run's first item, and `tokens=see:<firstItemShort>` (likewise
   `duration=`) on every other item, so per-item sums never multiply the run total. **Method B:** pass
   `--usage` only if a per-item file exists (`{"items": {"<short>": {"tokens": N, "duration": N}}}`);
   omit it otherwise (tokens/duration read `unknown`). Pass `--meta-dir` when per-seat `*.meta.json` files with
   the actually-reported model exist (gives `model-source` other than `self-report`); otherwise
   the line falls back to each stage's self-reported `modelReported`. This line is what the
   session-retrospective skill's structured-provenance parser reads — do not hand-write it, and
   do not reorder its fields.

---

## Step 4 — Batched advance

**One** `advance_item(transitions=[...])` call covering every item that passed Steps 1–2:
`trigger: "start"` for an item whose schema has a review phase, `trigger: "complete"` for one
that doesn't (mirrors the standard "prefer `complete` for an item's final transition" guidance —
here "final for this run" means "no review phase to enter", not "this is the item's last ever
transition"). An item that failed Step 1 or Step 2 is **excluded** from this call entirely — it
stays in its current phase for a human or a follow-up run to sort out, it does not get force-
advanced.

After the call:
- Collect every entry in the response's `unblockedItems` — these feed Step 9 of the main skill
  (they join the next frontier automatically, you don't need to re-derive them).
- Surface **every** `cascadeEvents` entry that carries `gateBlocked: true` — a cascade that
  itself got gate-blocked is not a silent no-op, tell the user which ancestor and which missing
  note blocked it.

Set `state.phase = "post-run"`.

---

## Step 5 — Review hand-off

Set `state.phase = "review"` before dispatching or handing off the reviewer — a resume landing
after this point (Resume matrix, `SKILL.md`) continues at Step 8 there instead of re-running
Step 4's batched advance.

`node "<helper>" review-prompt --plan <scratchpad>/run-wave/<runId>/plan.json --item <short>
--result <scratchpad>/run-wave/<runId>/result.json` per advanced item that entered a review
phase — **always pass `--result`**; without it the diff command falls back to a bare "owned
files: planner output" line instead of the real `git diff <baseSha>..HEAD -- <owned files>`
pathspec. Unless the project's `run-profile.json` sets `review: "handoff"` — in which case
control passes to the project's own review step instead (this repo: `/implement` Step 5) and you
do not dispatch the generic reviewer yourself. The generic review prompt is deliberately thin:
seat line, the owned-file diff command, which notes to fill, and which rule keys apply — it
carries no rule text inline (the reviewer fetches those itself).

**Wave parent review.** Before any reviewer for the wave parent itself is dispatched or handed off (a feature-level review; this repo's `/implement` Step 6 item 3), `get_context(itemId=<parent>)` and branch on its role. No review-role entry in its schema -> do not advance it: `start` from work with no review phase lands TERMINAL (`current/docs/api-reference.md:1237`; `container` is such a type, `.taskorchestrator/config.yaml:148-155`). Role `work` and `gateStatus.canAdvance` -> `advance_item` `trigger:"start"` (work->review) first, then dispatch. Role `work`, not `canAdvance` -> fill the orchestrator's own missing work notes or surface the seat-owned ones; never dispatch a reviewer at a work-role parent. Role `queue` -> run the `SKILL.md` Step 5 parent check first. Already `review` -> dispatch. Advance immediately before dispatch, not earlier: a REVIEW parent with every required note filled cascades to TERMINAL when its last child completes (`CascadeDetector.kt:130-163`, `current/docs/api-reference.md:1262-1266`).

**Security-assessment items.** The review-prompt helper does not know the item's schema. When the item's resolved schema has a `security-assessment` note (check `get_context` expectedNotes), append to the generated review prompt a line naming the worktree path beside the diff command, plus: "Apply the /security-review method to this item's owned-file diff in its worktree — `git -C <worktree> diff <baseSha>..HEAD -- <owned files>` — not by invoking the built-in command, which diffs the session cwd and is empty for a worktree branch."

**Fix cycle after a Fail.** When a review returns Fail, run the same four seats again, in order,
each scoped to one numbered amendment `A<n>` (A1 for the first Fail, A2 for the next). There is no
cap on cycles.

1. **Planner amends, by name.** Resume the item's planner seat with the blocking findings (via
   `SendMessage`). It appends a section `## Amendment A<n>` to its own planning note
   (`specification` for plugin-change, `task-scope`/`diagnosis` where the schema carries those) —
   never a rewrite of the frozen body above it, and never a separate note key, so the independence
   audit can still check that the note's freeze timestamp (`modifiedAt`) predates the fix commit.
   The amendment lists every existing test file it expects edited (planner-v1 `existingTestEdits`)
   and the mutation red-proofs (within the item's budget) the fix must satisfy. Keep amendments terse: accumulated ones made a
   50k `task-scope` in one run.
2. **Implementer, then blind test author, by name**, each scoped to section `A<n>` only. The test
   author appends its own `A<n>` section to `test-plan`/`test-manifest`; the planner does not touch
   those keys, which the test-author seat owns.
3. **Orchestrator runs the amendment's bug-fix revert, if it names one,** in a scratch copy, never
   the shared tree (same rule as Step 1), applying it as "Applying a mutation or a narrowest
   revert" above describes. The amendment's named budget mutations join the wave-boundary batch
   (Step 7a) with the rest of the item's budget.
4. **Original reviewer, by name**, scoped to `git diff <prevReviewedSha>..HEAD -- <owned files>`
   plus the blocking findings — see "Re-review after fixes" below.

**Fallback.** Resume-by-name works in Method B or when seats are dispatched by hand. Method A seats
run inside the implement-wave Workflow and generally cannot be resumed after it ends; there, and for
any seat that cannot be resumed, dispatch a fresh seat with id `<seat>-a<n>` (e.g. `planner-a1`)
and the same scope, so the actor audit and audit trail distinguish the cycle. The helper and verify
do not special-case `-a<n>` ids.

**Re-review after fixes.** If the original reviewer seat cannot be resumed once fixes land, dispatch a
fresh `task-orchestrator:reviewer` scoped to the fix commits (`git diff <prevReviewedSha>..HEAD -- <owned
files>`) plus the original blocking findings. The orchestrator never appends to or re-upserts a note
whose stored actor is a seat (`review-checklist`, `test-manifest`, `test-plan`,
`test-independence-audit`, or any other seat-owned key) — that flips its stored actor and turns the
seat's verdict into a self-confirmation. Any orchestrator confirmation goes under its own key,
`orchestrator-confirmation` (role review, optional; off-schema is fine).

**Recording out-of-run seats.** Every seat dispatched outside the run for an item (re-review,
fix-cycle `<seat>-a<n>`, amendment planner) is recorded by re-running
`node "<helper>" provenance ... --item <short> --extra-seats <seat>:<model>:<tokens>,...` — with the
same flags as Step 3, including `--usage` under Method A (omitting it on the run's first item
replaces the recorded run total with `tokens=unknown`) — and
re-upserting that item's `delegation-metadata` (orchestrator-owned, so no actor flip) with the
two-line output (grammar line, then `extra-seats=...`). The list is cumulative: each re-run passes
every out-of-run seat so far. Take tokens from the Agent result's usage; use `unknown` if not
reported. `--extra-seats` requires `--item`.

---

## Step 6 — Human checkpoints

These are not automatable from inside this skill; surface them and wait as the project's own
process requires:

- Arbitration of a red test authored by an independent test-author seat (per the project's
  arbitration rule — this repo: `/implement` Step 4b) is a **human or a separately-dispatched
  arbitration agent's** call, not this skill's.
- Opening a PR and merging it are the checkpoints that carry an item from review to terminal in
  most projects — this skill does not open PRs itself; that's the project workflow's job (this
  repo: `/implement` Step 6).
- Merge rounds that conflict on `CHANGELOG.md` (the `[Unreleased]` bullets) are resolved with
  `node claude-plugins/task-orchestrator/scripts/resolve-changelog.mjs --ancestor <f> --base <f>
  --branch <f> [--out <f>]`. It merges per bullet three-way: a one-sided edit or deletion is taken
  from the side that made it, and when both sides changed the same bullet differently the base
  branch's copy wins and the branch copy is printed to stderr as `DISCARDED [<section>] <key>`.
  A merge conflict on any other `appendOnlyFiles` file is resolved by keeping both sides' added lines.
  Exit 0 resolved, 1 duplicate heading (`DUPLICATE <kind> <name>`; fix by hand), 2 invalid input
  (`--out` is not written on 1 or 2). Stage mapping: `git show :1:CHANGELOG.md` is the ancestor;
  merging the base into the feature branch, `:2:` (ours) is the branch and `:3:` (theirs) is the
  base; in a rebase onto the base the two swap.

---

## Step 7 — Close and loop

Once the review hand-off (Step 5) is dispatched and any immediate human checkpoints (Step 6) are
surfaced, set `state.phase = "closed"`. Report, in one place, every item that was **deferred**
(cross-run edge, resource contention, run-size cap — with reasons), every item that was
**stopped** (invalid envelope, verify/actor-audit failure), and every item that was **excluded**
at planning time (unowned required note, unavailable schema config) — each with its specific
reason, not a bare count. Then return to `SKILL.md` Step 9: re-run Step 2 (a fresh snapshot),
letting the items surfaced in Step 4's `unblockedItems` join the new frontier.

Cleanup: once an item's seats have committed, delete its recovery snapshots with `git for-each-ref --format=%(refname) refs/wip/<short>` piped to `git update-ref -d` for each ref (`refs/wip/<short>` and `refs/wip/<short>-<seat>`). Leave a snapshot in place for any item that stopped without committing.

A `closed` run's state document and pointer line are left in place (`manage_plan_documents` has
no delete) — Step 0 F7's `phase != "closed"` filter is what keeps a finished run from being
mistaken for an open one on the next invocation.

---

## Step 7a — Wave-boundary red-proofs

Runs once the wave's PRs have merged (per-item mode: every item's PR; shared mode: the feature
PR, so for a shared wave this step follows the project's finalization) — never before a merge,
and never as a gate on one. It is the decision-point budget-mutation audit Step 1 deferred, done
once for the whole wave instead of once per item against the whole suite. Launch it in the
background and go on to `SKILL.md` Step 9; the next wave's planners do not wait for it.

1. **One scratch worktree at the merged SHA:** `git worktree add --detach <unique-tmp>
   <merged base branch SHA>` (`origin/main`, or the release branch the wave targets). Mutating
   the merged state is the point: it also catches a merge round that weakened an oracle, which a
   pre-merge per-item proof cannot see.
2. **Batch every item's budget** (`items[].redProof.shape` carried from Step 1, plus any fix-cycle
   amendment's named mutations; skip categories stay skipped). Apply each mutation with the
   anchored-patch helper ("Applying a mutation or a narrowest revert", Step 1), one mutation
   present at a time, the tree restored between them.
3. **Run each mutation scoped**, against the test classes the item's `test-manifest` maps to
   that scenario (`gradle-locked.ps1 -TestFiles "<those files>"`, which renders `--tests`), with
   `TO_GRADLE_OWNER=orchestrator` set inline. Red = the mapped tests failed. A survivor gets ONE
   whole-suite run, incremental, to tell "covered at another seam (name the test)" from a real
   gap; that is the only whole-suite run in this step.
4. **Triage survivors** per the rule: weak test, equivalent mutant, unreachable, or covered at
   another seam. A real gap does NOT reopen the item: it becomes a carry-in note on the next
   item that touches the same seam when one is queued, else one test-only follow-up item per wave
   (`/task-orchestrator:create-item`, type by the project's convention), which the policy exempts
   from further proofs. Escalate on evidence as the rule says — a real gap expands the audit to
   the rest of that item's scenarios, still scoped.
5. **Record once, on the wave parent** (the feature or container the run was scoped to; the
   single item when the run had one), under `red-proof-results`, actor `orchestrator:<session>`:
   one line per item — proofs run, red/survived, gaps, equivalents, approximate minutes, the
   scratch path and merged SHA. Reviewers of later waves read it there; nobody re-runs it.
6. **Remove the scratch worktree** (`git worktree remove --force <unique-tmp>`) and set
   `state.redProofs = "done"` on the run's state document so a resume does not repeat the step.
