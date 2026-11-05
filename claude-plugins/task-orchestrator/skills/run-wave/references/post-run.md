# Post-run protocol

Runs once a run's execution phase is done — either the Method A task notification arrives, or
Method B's own loop reaches `next → {complete: true}`. This is the same sequence for both
methods; the only difference is what triggers entry into it (see the main `SKILL.md` Step 7).

Each step below records the phase before it ends (`run-ops --phase update --run-phase <phase>`
under the coordinator token, and the local `state.json`), per the turn-boundary protocol in
`SKILL.md` — resume (F7 / the Resume matrix) re-enters at the first step not yet recorded. Every
coordinator call below carries the coordinator token as the top-level `seatToken`; read every
write's answer per element (`data.results[i]`, see RESULTS in `SKILL.md`).

---

## Step 0 — Load the result

Read the local `<scratchpad>/run-wave/<runId>/plan.json` (bound: it carries `args.serverRunId`) and
`state.json`. Save the notification's result JSON (Method A) or the accumulated `outs`/`stages`
from state (Method B) to `<scratchpad>/run-wave/<runId>/result.json`. Any item with no envelope
recorded at all — neither a successful `stage-result` nor a `stopped` marker — is treated as
`stopped: "agent returned null"`. A result with `started: false` (or a launch that threw) means
nothing ran: skip to Step 7 and close the run `failed`. Record the phase `notified`. Orchestrator post-run scripts write new, run-scoped file names and never write over a seat's artifact; run-planner's `--out <path>` refuses a launched run's existing files unless `--force`; a shell redirect (`> plan.json`) bypasses that guard, so write over a launched run's files only through `--out`.

An altitude-safe result (`altitudeSafe: true`, from a plan made with `--altitude-safe`) is saved and
verified the same way: it keeps `items[].stages` (status, commits, files, note keys) and the file
lists under `items[].outputs.<seat>`, plus a `rollup` of counts, and cuts every reason to 120
characters. The seat prose (planner decisions, corrections, deviations) is not in it; read it from
the seats' notes when a later step needs it. `redProofShape` survives, cut to 200 characters; Step
7c reads the full shape from the test-plan note's `red-proof-shape:` line. A `reseat` from a safe
result passes scrubbed outputs, so a fix-cycle prompt shows `decisions: none` and
`diagnosisCorrections: none`: tell the reseated seat to read the item's queue notes, and give a
test-author reseat `--declarations`.

`runs_read(select:"get", runId:<serverRunId>)` shows the run's status: a `paused` run (`pauseReason`
budget, attempts or manual) is reported now: send one `paused` report (`report --event paused
--run <run.json>`, with `runs_read` `get` saved as `run.json`), and one `decision` report for the
stages stopped `awaiting_decision` (their `decisionId`s are in `result.json`). Post-run work that
needs no new seat continues, and the run closes as usual.

---

## Step 0a — Report usage

Method A: write the Workflow task notification's usage to `<scratchpad>/run-wave/<runId>/usage.json`
as `{"tokens": N, "duration": N}` (add `"costUsd": N` when the notification reports a cost).
That file is always written here, but the heartbeat is SENT here only when no run item has a review
phase (Step 4 sends no `start`, Step 5 assigns no reviewer). Otherwise it is sent at Step 7, after the
Step 0b re-sweep and just before `close`, because a heartbeat that crosses the budget pauses the run
and the later `start` and reviewer `assign` would answer `run_paused`. Residual: a Step 5 wave-parent
`start` under the coordinator token can still answer `run_paused` after a Step 0a heartbeat crossed
the budget; report it (`paused`), and a non-member parent may be started token-less.
`node "<helper>" run-ops --plan <plan> --phase heartbeat --usage <usage.json>` renders an `advance`
call `[claim(first item), heartbeat{usage: {tokens, costUsd?}}, release]`; add `--item <short>` to
claim a named item instead (at Step 7 use the first item Step 4 sent `start` for: it is in review,
so not terminal). Send it under the coordinator token (a heartbeat's `usage` needs a seat token and
a held claim, so the coordinator claims that member for the length of this one call). This reports the run total once:
the server keeps the highest value per seat. Any element error is Friction, never retried, and never
blocks the close. A success carrying `paused: {decisionId}` means the report crossed the run budget:
surface the decision (at Step 7 it is reported with `openDecisions` in the `closed` report). Method B: report the same way when a run total is known; otherwise skip.

---

## Step 0b — End the seats

`runs_read(select:"seats", runId:<serverRunId>)` lists the active seats; save its `data` to
`<scratchpad>/run-wave/<runId>/seats.json`. Then `node "<helper>" run-ops --plan <plan> --phase
end-seats --seats <seats.json> --result <result.json>` renders one `end_seat` element per still-active
member seat (outcome `done` for a done stage, `failed` for a stopped one, `abandoned` otherwise; the
coordinator row and ended seats are skipped) with the aligned `tokenFiles`. Send each element in a
call of its own under the token read from its `tokenFiles[i]` (the coordinator token is refused on
`end_seat`). An `unauthenticated` element means that seat was superseded or ended in the meantime:
record it and go on. A seat that ended itself is not listed. `close` with reason `completed` is
refused while a seat is active, so this sweep always runs before Step 7.

---

## Step 1 — Verify

`node "<helper>" verify --plan <scratchpad>/run-wave/<runId>/plan.json --result <scratchpad>/run-wave/<runId>/result.json` — **exits 0 when every item is `ok`, 3 when any item is not**
(the CLI gathers `gitFacts` itself: `git cat-file -e` per declared commit SHA, plus a `git log`
walk per distinct worktree named in the plan). Read the JSON body regardless of exit code — a
non-zero exit here is an expected result, not a call to retry. Per item this checks: the declared
commit SHAs actually exist, each stage's committed file list is a subset of what that seat was
allowed to touch (implementer ⊆ `mainFiles ∪ docFiles`; test-author ⊆ `testFiles ∪
existingTestEdits[].file`; never cross-owned), commit subjects carry the `[<short>]` tag and a
`Seat:` trailer, the item's committed text files read at the worktree HEAD are valid UTF-8 with no double-encoded
(mojibake) sequences (a `<file>:<line> non-UTF-8 or double-encoded text` finding; binary files are skipped), and it returns `items[].redProof.shape` - the red-proof budget the planner
derived for that item (`null` when the item had no planner-v1 output to derive one from). An
untagged commit (no `[<short>]` in its subject) surfaces as a top-level `warnings` entry, not a
per-item finding, and does not by itself fail the item.

**`appliesTo` and `knownRed`.** `orchestrator`-seat entries in `redProof.commands` carry `appliesTo` when the
profile declares it: run such an entry only when a path in `git diff --name-only <baseSha>..HEAD` matches one
of its patterns. The verify output's top-level `knownRed` lists tests already known red (each with `expires` or
`tracking`): report them once, do not triage them as regressions.

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
repo's profile names `wave-build` (the locked `:current:test` and `:current:ktlintCheck` run) and
`plugin-node-tests` (the plugin `scripts/tests` and `hooks/tests` node suites).

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

## Step 2 — Notes audit

`notes_read(select:"list", itemId:<item>, keys:[<that item's seat notes>])` for every item in the
run (list answers carry no bodies); write the observed `{itemId, key}` rows to
`<scratchpad>/run-wave/<runId>/observed-notes.json`. Then run `node "<helper>" notes-audit --plan
<scratchpad>/run-wave/<runId>/plan.json --notes <scratchpad>/run-wave/<runId>/observed-notes.json
--result <scratchpad>/run-wave/<runId>/result.json` — passing `--notes` is what turns this call from
*listing* the expected table (`{itemId, key, seat, optional?}`) into *auditing* observed against
expected. **Always pass `--result`**: it limits the expected notes per item to stages whose result
status is actually `done`, so an item whose stages were deferred or never ran is reported
`skipped: true` (not a `missing`-notes failure). It **exits 0 when no item has a `missing` entry (or
is `skipped`) and 3 otherwise**; as with `verify`, read the JSON body regardless of exit code.

An item carrying any `missing` entry **fails this run**, same as a Step 1 verify failure — do not
advance it. Who wrote a note is not audited here: the server enforces each seat's lane (a write
to another seat's key is refused `note_owned_by_other` with `ownerSeat`, a forbidden action
`seat_forbidden`), so a filled note is its seat's.

An **optional** note (a stage's `optionalNotes`; the expected table marks it `optional: true`) that
was never written is **not** `missing` and does not fail the item. In a seat-less plan
`delegation-metadata` is not a seat's note: the planner lists it under the item's
`orchestratorNotes`, no seat prompt may write it, and the orchestrator records it in Step 3.

---

## Step 3 — Orchestrator notes

Two notes, written by you with `notes_write` `upsert` (body inline) under the coordinator token
(top-level `seatToken`): the coordinator token may write the `orchestrator`-seat keys of run members,
and a token-less write to such a key is refused. They are the front door's own notes about the run,
filled after Steps 1–2 have concrete results to report. A note created under the coordinator token is
owned by that pair, so write its final content once, in confirmed past tense; the run-wave pointer line (in `run-pointers`; `session-tracking` for a feature started before that note existed or a schema without the key), the cumulative
`delegation-metadata` re-upsert and the `session-tracking` closure rewrite after a fix cycle (Step 5 "Fallback", step 5) are the only re-edits, each under the coordinator token of an open run
over the item. Inside an open run a human credential may also write it; once the run
is closed, a different (principal, seat) pair needs an admin takeover. On `note_owned_by_other`: with no
`detail.ownerSeat` the note was created token-less earlier, so re-send once without the token; with
`detail.ownerSeat` present leave it and report it. Never `takeover`.
If one of the item's plan stages lists `session-tracking` in `stages[].notes` (a seat-less schema: the
implicit implementer owns it), skip `session-tracking` for that item and record its outcome in the
run report instead.

1. **`session-tracking`** — outcome per item, files touched, deviations from the plan, and
   Friction (the declarations-scan hit count from Method B step 4, any Step 0 fallback taken in
   `SKILL.md`, any `verify`/notes-audit failure, a failed usage report). Write this **after**
   verify and the notes audit have run, in confirmed past tense — not as a speculative "pending
   verification" draft you intend to edit later. When the item's schema has a review phase (a
   review-role note, or a review trait such as `needs-docs-accuracy-review`), the confirmed state
   is "implemented, review pending": write `Outcome: implemented, review pending`, label the
   deviations "Deviations (before review)", and end the body with the mandatory line
   `Review: pending - verdict in <review-note key>; fix cycles are recorded in the item's amendments note or its planning note's Amendment A<n> sections`.
   `Outcome: success` is reserved for items whose schema has no review phase.
2. **`delegation-metadata`** — the single provenance line, produced by
   `node "<helper>" provenance --plan <plan> --result <result> --method A|B --turns <n> --usage <usage-file> [--meta-dir <dir>]`. **Method A: `--usage` is mandatory** (the Step 0a
   file): the helper records that run total once, on the run's first item, and
   `tokens=see:<firstItemShort>` (likewise `duration=`) on every other item, so per-item sums never
   multiply the run total. **Method B:** pass `--usage` only if a per-item file exists
   (`{"items": {"<short>": {"tokens": N, "duration": N}}}`); omit it otherwise (tokens/duration read
   `unknown`). Pass `--meta-dir` when per-seat `*.meta.json` files with the actually-reported model
   exist (gives `model-source` other than `self-report`); otherwise the line falls back to each
   stage's self-reported `modelReported`. A bound plan appends `server-run=<uuid>` after the fixed
   fields. This line is what the session-retrospective skill's structured-provenance parser reads —
   do not hand-write it, and do not reorder its fields.

---

## Step 4 — Batched advance

**Live-owed hold first.** For each item that passed Steps 1–2, read its `session-tracking` note
(`notes_read(select:"get", itemId, key:"session-tracking")`): a `**Live-owed**:` line naming an
open entry holds that item in work — leave it out of the call below and report it. When the line
is absent, read the item's spec note's State matrix the same way; no line and no open matrix
entry means no hold. A live-owed item whose only open entry needs a refreshed plugin cache is the case
the Step 7b post-merge split handles once the PR merges.

**One** `advance` call, under the coordinator token, with one `transition` element per item that
passed Steps 1–2 and is not held: `trigger: "start"` for an item whose schema has a review phase,
`trigger: "complete"` for one that doesn't ("final for this run" means "no review phase to enter",
not "this is the item's last ever transition"). An item that failed Step 1 or Step 2 is
**excluded** from this call entirely — it stays in its current phase for a human or a follow-up run
to sort out, it does not get force-advanced.

After the call, save its answer to `<scratchpad>/run-wave/<runId>/advance.json` and run
`node "<helper>" follow-ups --in <advance.json>`, which returns `{unblocked, seatBlocked,
gateBlocked, reviewPending, gateMissing}` (item ids):

- `unblocked` — these feed Step 9 of the main skill (they join the next frontier automatically).
- `seatBlocked` — a cascade into a parent stopped `blocked: seat`: the parent is a member of
  ANOTHER run that is not closed, and your coordinator token does not cover it. Do not re-send; report
  it. That run's coordinator or a human advances the parent.
- `gateBlocked` — a cascade stopped at a parent missing required notes (`gateMissing[<id>]`): tell
  the user which ancestor and which notes; it is not a silent no-op.
- `reviewPending` — a terminal cascade into a parent in review is always suppressed: the parent
  leaves review only by an explicit advance (Step 5 "Wave parent review", Step 7b). Informational.
- An element error is per element (`data.results[i].error`): `run_paused` stops this step and is
  surfaced; anything else is reported for that item.

Record the phase `post-run`.

---

## Step 5 — Review hand-off

Record the phase `review` before dispatching or handing off the reviewer — a resume landing after
this point (Resume matrix, `SKILL.md`) continues at Step 8 there instead of re-running Step 4's
batched advance.

**Under `review: "handoff"`** (the project's `run-profile.json`), run Step 7 first — the run closes
before the project's own review step (this repo: `/implement` Step 5) — and do not dispatch the
generic reviewer yourself. The project's review step owns merge arming; do not arm auto-merge from
post-run.

**Otherwise**, per advanced item that entered a review phase: on a bound run, first assign its
reviewer lane like any schema seat — `node "<helper>" run-ops --plan <plan> --phase assign --item
<short> --seat reviewer`, sent under the coordinator token, the `seatToken` written to the rendered
token file (a token-less write to a seat-owned key of a run member is `note_owned_by_other`). Then
`node "<helper>" review-prompt --plan <scratchpad>/run-wave/<runId>/plan.json --item <short>
--result <scratchpad>/run-wave/<runId>/result.json` — **always pass `--result`**; without it the
diff command falls back to a bare "owned files: planner output" line instead of the real
`git diff <baseSha>..HEAD -- <owned files>` pathspec. The generic review prompt is deliberately
thin: seat line, the owned-file diff command, which notes to fill, which rule keys apply and, on a
bound run, the token file and END SEAT — it carries no rule text inline (the reviewer fetches those
itself) and never the token. The review-wave workflow (`review-wave/args-v1`) takes the same
`serverRunId` and a per-stage `tokenFile`; lanes that share a seat share one assignment and one
token file, and its lanes never end their seat, so run Step 0b's sweep again before Step 7.

**Review wave launch.** When this post-run actor has the `Workflow` tool (re-probe F1: a background
subagent may lack it) and the run is bound, assign each reviewer seat once (`run-ops --phase assign
--item <short> --seat reviewer`, token file written as above), then `node "<helper>" review-args
--plan <plan.json> --snapshot <scratchpad>/run-wave/<runId>/snapshot.json --result <result.json>
--items <short,short,...>` prints the `review-wave/args-v1` object (lanes are the item's review-role
notes grouped by seat and skill; exit 2 names an item with no review notes). Call
`Workflow({name: "task-orchestrator:review-wave", args: <that object, as a real object>})`, record
`reviewWave.taskId`, `reviewWave.wfRunId` and `reviewWave.wfScriptPath` in `state.json`, and end the
turn. On the notification, aggregate the lane verdicts, run Step 0b's sweep, and continue. Without
`Workflow`, keep the generic `review-prompt` path above.

**Wave parent review.** Before any reviewer for the wave parent itself is dispatched or handed off
(a feature-level review; this repo's `/implement` Step 6 item 3), `items_read(select:"context",
itemId:<parent>)` and branch on its role. No review-role entry in its schema → do not advance it:
`start` from work with no review phase lands terminal. Role `work` and `gateStatus.canAdvance` →
`advance` `transition` `start` (work→review) under the coordinator token first, then dispatch. Role
`work`, not `canAdvance` → fill the orchestrator's own missing work notes or surface the seat-owned
ones; never dispatch a reviewer at a work-role parent. Role `queue` → run the `SKILL.md` Step 5
parent check first. Already `review` → dispatch. On 4.0 a review-role parent is never moved by a
child's terminal cascade (it is suppressed with reason `review-pending`), so the parent can enter
review as soon as its work notes are filled.

**Security-assessment items.** The review-prompt helper does not know the item's schema. When the
item's resolved schema has a `security-assessment` note (check `items_read(select:"context")`'s
`schema` list), append to the generated review prompt a line naming the worktree path beside the
diff command, plus: "Apply the /security-review method to this item's owned-file diff in its
worktree — `git -C <worktree> diff <baseSha>..HEAD -- <owned files>` — not by invoking the built-in
command, which diffs the session cwd and is empty for a worktree branch."

**Fix cycle after a Fail.** When a review returns Fail, run the same four seats again, in order,
each scoped to one numbered amendment `A<n>` (A1 for the first Fail, A2 for the next). There is no
cap on cycles.

1. **Planner amends, by name.** Resume the item's planner seat with the blocking findings (via
   `SendMessage`). It writes the ruling into the item's `amendments` note (queue, planner seat;
   rewritten in full each cycle per its guidance) and never edits the frozen planning note, so
   `task-scope`, `diagnosis` or `specification` keeps its freeze `modifiedAt` for the independence
   audit. On a schema with no `amendments` key (plugin-change), append `## Amendment A<n>` to
   `specification` under the same length rule. An amendment states rulings and behavioural
   changes; the line-level trace belongs to the implementer's commit and the reviewer's re-check,
   not here. The ruling lists every existing test file it expects edited (planner-v1
   `existingTestEdits`) and the mutation red-proofs (within the item's budget) the fix must
   satisfy. Keep amendments terse: accumulated ones made a 50k `task-scope` in one run.
2. **Implementer, then blind test author, by name**, each scoped to ruling `A<n>` only. The test
   author appends its own `A<n>` section to `test-plan`/`test-manifest`; the planner does not touch
   those keys, which the test-author seat owns.
3. **Orchestrator runs the amendment's bug-fix revert, if it names one,** in a scratch copy, never
   the shared tree (same rule as Step 1), applying it as "Applying a mutation or a narrowest
   revert" above describes. The amendment's named budget mutations join the wave-boundary batch
   (Step 7a) with the rest of the item's budget.
4. **Original reviewer, by name**, scoped to `git diff <prevReviewedSha>..HEAD -- <owned files>`
   plus the blocking findings — see "Re-review after fixes" below.

**Late carry-ins and orchestrator-originated fixes.** A carry-in that arrives after planning, or a
fix the orchestrator originates, is applied through an amendment: seat `planner-a<n>` with the
helper's `reseat` command (see Fallback below); it reads the carry-in and the cited source, writes
the `A<n>` amendment with the exact text, and `implementer-a<n>` applies it. On an item with no
planner stage, dispatch the amendment planner by hand and record it with `--extra-seats` (see
below). The orchestrator states the obligation and its source, never the replacement prose. A seat
applies orchestrator text verbatim only for a mechanical edit: a path, an id or a version string. A
pre-launch late carry-in on an item with no planner stage (the `lateCarryIns` of SKILL.md Step 3)
keeps its orchestrator disposition line, which states the obligation and source only.

**Dispositions at child close.** When a child's review returns Pass or Pass with observations,
write the child's `orchestrator-confirmation` before advancing it: one line per non-blocking
finding id (`O#` from a review-checklist, `N#` from a docs-accuracy or similar review; follow-up
item created first, or `accepted: <reason>`), `N of N disposed`. Nothing is left for the parent
review to carry.

**Fallback.** Resume-by-name works in Method B or when seats are dispatched by hand. Method A seats
run inside the implement-wave Workflow and generally cannot be resumed after it ends; there, and for
any seat that cannot be resumed, dispatch a fresh seat with id `<seat>-a<n>` (e.g. `planner-a1`, valid when the item has a planner stage)
and the same scope, so the audit trail distinguishes the cycle. The server knows only schema seats,
so a fix-cycle seat runs under its **base** seat's assignment: on a still-open run, first re-`assign`
the base seat (`run-ops --phase assign --item <short> --seat <base seat>`, coordinator token; this
supersedes the old assignment as attempt n+1) and write the new `seatToken` to the base seat's token
file. When the run is already closed (for example after a `review: "handoff"` close), the cycle gets its own
run over that one item, `<runId>-a<n>`. Follow these steps in order:

1. Open a run over that one item, label `<runId>-a<n>`. `open` carries no `seatToken`; save the returned
   `coordinatorToken` as one line to its own token file beside the run's (for example
   `tokens/coordinator-a<n>.token`, never over `tokens/coordinator.token`), and never echo it.

```to-call
{"tool": "runs_write", "op": "open", "args": {"rootId": "<rootId>", "items": ["<itemId>"], "label": "<runId>-a<n>"}}
```

2. Bind a copy of the plan to the new run: `bind-run --in plan.json --server-run <uuid>`, written to
   `plan-a<n>.json`.
3. Re-`assign` the base seat under this run's coordinator token (`run-ops --phase assign --plan plan-a<n>.json`:
   run-ops reads the runId from the bound plan, and `plan.json` still targets the closed run) and write its
   `seatToken` to the base seat's token file.
4. Reseat each fix-cycle seat with `reseat --plan plan-a<n>.json` (the command below).
5. After the re-review verdict, rewrite the item's `session-tracking` under this run's coordinator
   token: the `Review: pending - ...` line becomes `Review: <verdict> after A<n> - <review-note key>`
   and the Outcome line becomes `Outcome: implemented, review <verdict> after A<n>` (final on a Pass;
   a Fail opens cycle `a<n+1>` with its own run). In the same step, before the close, re-upsert
   `delegation-metadata` per "Recording out-of-run seats".
6. Close the run (`runs_write` `close`, `runId` = this run's uuid, `reason` `completed`, coordinator
   token). `completed` is refused while a seat is active, so every seat of this run, a re-review seat included, has ended its seat first.

Render the seat's prompt with the helper's `reseat` command instead of writing it by hand:

```
node "<helper>" reseat --plan <plan.json> --run <runId> --result <result.json> \
  --item <short> --seat <seat>-a<n> [--declarations <file>] [--model <m>] [--tokens <n>] [--pre <sha>] [--assignment <uuid>]
```

`--seat` must be a stage seat of the item plus `-a<n>` (n >= 1, no leading zero); a reviewer seat
is not reseated (use `review-prompt`). The prompt is the base stage's prompt under the `<seat>-a<n>`
commit trailer, with no ENTRY block, the base seat's token file and END SEAT on a bound plan
(`tokenFile` in the output), and a `FIX CYCLE A<n>` line. A test-author base on an item with a
declarations stage needs declarations from the result or `--declarations <file>` (plain text;
`none` or empty counts as absent), else the command exits 3 with `missingDeclaration:`. The output
JSON carries a `result` copy with the seat appended to `items[].reseats`
(`{seat, baseSeat, cycle, model, tokens, pre, assignment?}`; `pre` defaults to the item worktree's
`HEAD`, is recorded once and never moves; `assignment` is the `assignmentId` the base seat's
re-`assign` answered): write it back to `result.json`. Verify then judges the
test-author-before-implementer order only over item commits at or before the earliest recorded
`pre`, so an implementer-after-author fix cycle is accepted; a `pre` outside the run's history is a
finding. Verify trusts the ledger `pre`: a `--pre` earlier than an order violation hides it, so pass the real pre-fix `HEAD`.

**Re-review after fixes.** If the original reviewer seat cannot be resumed once fixes land, dispatch a
fresh `task-orchestrator:reviewer` scoped to the fix commits (`git diff <prevReviewedSha>..HEAD -- <owned
files>`) plus the original blocking findings. The orchestrator never writes a note a seat owns
(`review-checklist`, `test-manifest`, `test-plan`, `test-independence-audit`, or any other seat-owned
key): the server refuses it (`note_owned_by_other`), and a confirmation must never come from the
seat's own verdict being rewritten. Any orchestrator confirmation goes under its own key,
`orchestrator-confirmation` (role review, optional; a declared note with seat orchestrator on
feature-task and bug-fix, no seat on feature-implementation).

**Recording out-of-run seats.** Every seat dispatched outside the run for an item (re-review,
fix-cycle `<seat>-a<n>`, amendment planner) is recorded by re-running
`node "<helper>" provenance ... --item <short> --extra-seats <seat>:<model>:<tokens>,...` — with the
same flags as Step 3, including `--usage` under Method A (omitting it on the run's first item
replaces the recorded run total with `tokens=unknown`) — and
re-upserting that item's `delegation-metadata` (orchestrator-owned) under the coordinator token of
the run holding the item (the still-open run, or the fix-cycle run before it closes; with no open run the
write is refused, so report it) with the two-line output (grammar line, then `extra-seats=...`). The list is cumulative: each re-run passes
every out-of-run seat so far. Take tokens from the Agent result's usage; use `unknown` if not
reported. Seats recorded by `reseat` are read from the `reseats` ledger in `--result`, listed first,
so `--extra-seats` is only needed for seats `reseat` never saw (re-review, or an amendment planner on an item with no planner stage); a seat
named in both takes the flag's model and tokens. `--item` alone now restricts the output to that
item; `--extra-seats` requires `--item`.

---

## Step 6 — Human checkpoints

These are not automatable from inside this skill; surface them and wait as the project's own
process requires:

- Arbitration of a red test authored by an independent test-author seat (per the project's
  arbitration rule — this repo: `/implement` Step 4b) is a **human or a separately-dispatched
  arbitration agent's** call, not this skill's.
- A decision a seat raised (`awaiting_decision`, with its `decisionId`) or a pause decision of the
  run is answered by a human or a director, never by the coordinator. Each one is reported with a
  `decision` report (a decision the coordinator raises itself is reported the same way).
  For each item stopped `awaiting_decision`, read `items_read(select:"get")`. With no `decisionId`
  (a token-less seat could not raise), the coordinator raises under the coordinator token with
  `block: true`, building the question and options from the seat's `## Deviations requested`
  (see status-progression/references/decisions.md). When it is `resumable` before the run closes,
  resume it under the coordinator token and carry the ruling; Step 9's re-snapshot re-dispatches it.
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
- A merge round git completes without conflict is not yet a semantic merge. Merge the base with
  `git merge --no-commit --no-ff <base>`, then list the files both sides changed: with `<mb>` =
  `git merge-base HEAD MERGE_HEAD`, intersect `git diff --name-only <mb> HEAD` (the branch side) and
  `git diff --name-only <mb> MERGE_HEAD` (the base side). For each prose file in the intersection
  (`.md`, agent, rule and skill files), read the merged region for duplicated or contradictory
  paragraphs and fix them before committing; for code, run the run profile's `verify[]` entries that
  name the `orchestrator` seat. Record `both-sides files: <list>, checked` (or `both-sides files:
  none`) in the merge commit's body and in the feature parent's `session-tracking` at finalization.

---

## Step 7 — Close and loop

Once the review hand-off (Step 5) is dispatched or handed off, any review seats of this run have
been swept (Step 0b again), and any immediate human checkpoints (Step 6) are surfaced, send the usage
heartbeat now when Step 0a deferred it (any run item has a review phase): `node "<helper>" run-ops
--plan <plan> --phase heartbeat --usage <usage.json> --item <short>`, where `<short>` is the first item
Step 4 sent `start` for (it is in review, so not terminal). Send it under the coordinator token, then
close the run: `node "<helper>" run-ops --plan <plan> --phase close --result <result.json>` renders
`{op: "close", runId, reason}` — `completed` when the result says `started: true`, `failed` when it
says `started: false` or the launch threw (no result); pass `--reason cancelled` for an explicit user
stop or `--reason abandoned` for a Resume re-plan of an unrecoverable run (any other `--reason` exits
2). Send it under the coordinator token. `invalid_run_state` on a `completed` close means a seat is
still active: run Step 0b's sweep, then close again. The answer's `snapshot` carries `openDecisions`,
the number of decisions still open (a close withdraws none): save the close answer as `close.json`, then send the `closed` report
(`report --event closed --result <result.json> --close <close.json>`).

Report, in one place, every item that was **deferred** (cross-run edge, resource contention,
run-size cap — with reasons), every item that was **stopped** (invalid envelope, verify/notes-audit
failure, `awaiting_decision`, `run_paused`, `seat-token-rejected`, `seat-token-missing`), every item **held** live-owed,
and every item that was **excluded** at planning time (unowned required note, unavailable schema
config) — each with its specific reason, not a bare count. Suggest `/session-retrospective --run
<serverRunId>` for the retrospective of this run. Then return to `SKILL.md` Step 9: re-run Step 2
(a fresh snapshot), letting the items surfaced in Step 4's `unblocked` join the new frontier.

Cleanup: once an item's seats have committed, delete its recovery snapshots with `git for-each-ref --format=%(refname) refs/wip/<short>` piped to `git update-ref -d` for each ref (`refs/wip/<short>` and `refs/wip/<short>-<seat>`). Leave a snapshot in place for any item that stopped without committing. Delete `<scratchpad>/run-wave/<runId>/tokens/` once the run is closed: its tokens are dead.

A closed run stays readable (`runs_read` `get`) and its pointer line (in `run-pointers`, or the `session-tracking` fallback) stays in place — Step 0 F7
reads only open and paused runs, so a finished run is never mistaken for an open one.

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
   A carry-in note is a requirement, not a hand-off dump: the source (item and finding id, or
   the red-proof survivor), what the receiving item must do in at most five lines, how it is
   verified (one command), and the acceptance criterion it should become. Anything that enumerates
   files or lines goes in a `page` document the note names by slug. Target under 1200 characters.
5. **Record once, on the wave parent** (the feature or container the run was scoped to; the
   single item when the run had one), under `red-proof-results` (`notes_write` `upsert`, no
   token): one line per item — proofs run, red/survived, gaps, equivalents, approximate minutes,
   the scratch path and merged SHA. Reviewers of later waves read it there; nobody re-runs it.
6. **Remove the scratch worktree** (`git worktree remove --force <unique-tmp>`) and set
   `redProofs: "done"` in the run's local `state.json` so a resume does not repeat the step.

---

## Step 7b - Feature closure

Applies to a parent with 2 or more children whose resolved schema has a review phase (this repo:
`feature-implementation`), once EVERY child is terminal (a child that is only a live check needing a
refreshed plugin cache or a user opt-in is the exception: see "Post-merge live acceptance" below). Shared mode: before the feature PR is
pushed, so before Step 7a. Per-item mode: after the last child's PR has merged; it does not wait
on Step 7a. Not covered here: a parent with one child (the project workflow's finalization decides how its
independent parent review runs; this repo: `/implement` Step 6) and a
parent whose schema has no review phase (it cascades to terminal when its last child completes and
its required notes are filled).

**Why every child first.** The feature review judges the whole merged range, so it waits for the
last child. On 4.0 the old hazard is gone: a terminal cascade into a parent in `review` is always
suppressed (`blocked: gate`, reason `review-pending`), so finishing a child never closes a
review-role parent, and the parent leaves review only by the explicit advance in item 4.

1. **Dispatch one feature reviewer.** The parent is in review (Step 5 "Wave parent review").
   Dispatch one `task-orchestrator:reviewer` with an explicit `model`: the model the parent's
   review dispatch profile names, else `opus`. Range: the merged diff of the feature's owned files,
   pathspec = the union of the children's owned files (the reviewer also runs `git diff --stat
   <range>` over the WHOLE range, without the pathspec, and flags any off-scope change; this
   whole-range check applies in shared mode, while in per-item mode compare each child PR's merged
   diff instead, since the base merges in between are not the feature's changes): shared mode
   `git -C <feature-worktree> diff <base>...HEAD -- <paths>`; per-item mode the same pathspec over
   the base-branch range from the SHA before the first child merged to the merged tip, so unrelated
   base merges stay out. Input: the verdict line of each child's findings register (its
   `review-checklist`, or the first review-role note its reviewer filled when the schema declares
   none) and its `orchestrator-confirmation` count line (`notes_read(select:"get", ...)` per
   child; read the bodies, hand over the two lines), plus any child disposition line reading
   `follow-up` whose item does not exist yet. List any child without a review as such. The
   reviewer judges the merged range
   and writes only feature-level findings (cross-child seams, off-scope changes, the merged result
   of the children together); a child's already-disposed observation is never re-listed,
   re-numbered or "carried". The reviewer fills the parent's review-phase notes; the orchestrator
   never rewrites them.
2. **On Fail**, the default fix path is a fix seat (a fresh `implementer-a<n>`, scoped to the
   finding's file) against the PARENT feature: its dispatch prompt must name it a NON-ENTRY seat
   that never transitions the parent. In shared mode it commits on the feature branch; in per-item
   mode there is no feature branch, so the fix lands as its own small PR against the base branch
   and the re-check reads that PR's diff. Either way it carries a `Seat: implementer-a<n>` trailer
   and a `[<parent-short>]` tag, and the orchestrator records the dispatch in the parent's
   `delegation-metadata`. Because a review-role parent no longer cascades to terminal, a child fix
   path is also allowed when the finding belongs to one child's files: `reopen` that child, let its
   own seats fix it, and complete it again — the parent stays in review. Prefer the parent seat
   unless the fix clearly belongs to one child. The SAME feature reviewer then re-checks only the
   fixed findings (a fresh reviewer seat when the original cannot be resumed). A blocking finding
   can only be disposed `fixed`.
3. **Dispose every finding.** In the parent's `orchestrator-confirmation` (role review, written by
   you with `notes_write` `upsert`), one line per finding:
   `<id>: fixed <sha|PR> | follow-up <item-id> | accepted: <reason>`, ending with
   `<N> of <N> disposed`. A follow-up item must exist before its line is written
   (`/task-orchestrator:create-item`).
4. **Complete.** The orchestrator's explicit `advance` `transition` `complete` on the parent, only
   after N of N.

**Post-merge live acceptance.** After the feature PR has merged (a `review: "handoff"` close applies
it too: the project's finalization, this repo `/implement` Step 6, carries the same step), if the
only non-terminal children are live checks that need a refreshed plugin cache or a user opt-in: (a)
create one `plugin-change` item titled `Post-merge live acceptance: <feature title>` under the
feature's parent, its `specification` filled at creation; (b) `deps_write` `create` a `relates_to`
edge from it to the feature; (c) re-parent each such check under it (`items_write` `update`
`parentId`); (d) add one line `Post-merge live acceptance: <holder short id> (<check short ids>)` to
the feature's `session-tracking`, token-less when that note was created token-less (it stays re-writable after the runs close), else in `orchestrator-confirmation`, since per Step 3 a note created under a coordinator token takes a re-edit only inside an open run; (e) complete the feature. The holder's specification opens with
this fixed next-session preamble: (1) purge the plugin cache and update the marketplace, per
`claude-plugins/CLAUDE.md` "Plugin Discovery and Cache Refresh"; (2) after the next session starts,
grep the cached files for one string the change added (name the string); (3) the user's opt-in for
any check that runs the Workflow tool. Then one line per re-parented check. The next session runs
the checks, fills the holder's `session-tracking` and completes it. Create the holder and re-parent
the checks before (e): a terminal auto parent takes no new children. The complete in item 4 follows
steps (a)-(d); in shared mode, where Step 7b runs before the feature PR is pushed, the split and item 4
therefore wait for the merge. No server gate enforces the split: it is discipline, like the dispositions below.

**Honest limit.** No server gate and no helper checks dispositions: `orchestrator-confirmation`
is a declared optional note, so this is discipline, not enforcement. Under `review: "handoff"` the
project's finalization applies the same rules (this repo: `/implement` Step 6 Parallel).
