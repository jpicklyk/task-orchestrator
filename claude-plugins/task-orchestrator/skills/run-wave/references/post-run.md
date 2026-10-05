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
`Seat:` trailer, and it returns `items[].redProof.shape` — the red-proof checklist the planner
derived for that item (`null` when the item had no planner-v1 output to derive one from). An
untagged commit (no `[<short>]` in its subject) surfaces as a top-level `warnings` entry, not a
per-item finding, and does not by itself fail the item.

**How ownership is matched.** Declared files and changed paths are both normalized to
repo-relative POSIX before comparison: `\` becomes `/`, a leading `./` and any trailing `/` are
dropped, and a leading root prefix is stripped (case-insensitively for drive-letter paths). The
roots come from git per worktree (`git rev-parse --show-toplevel`, plus the parent of
`--git-common-dir` for the main checkout); with no roots available, the item's `worktree` is the
only root. An absolute path outside every root never matches and is reported. A declared
directory (with or without a trailing `/`) owns every file beneath it, new or modified, and a
declared entry containing `*` or `?` is a glob (`**` spans segments, `*` stays within one,
`?` is one non-`/` character) — this applies to `testFiles` globs too. **Cross-item precedence:**
if a file is exactly declared (non-glob) by another item in the run and not exactly declared by
the writer, it is reported as `implementer wrote <file> owned by item <short>` (or
`test-author wrote ...`) even when the writer's own directory or glob would cover it. A file
outside everything the writer declared stays `implementer wrote unowned <file>`.

**Symbolic refs.** A stage `commits.pre`/`post` that is not a 7-40 character lowercase hex SHA
(for example `HEAD`) is resolved in the item's worktree with `git rev-parse --verify
<ref>^{commit}` rather than `cat-file`. Each item row carries `resolvedRefs: [{seat, field, ref,
sha}]` recording the SHA. An unresolvable ref fails the item with `unresolved ref <ref> (<seat>
<field>)`, and a resolved `post` that is not one of that item's `[<short>]`-tagged commits fails with
`ref <ref> resolved to <sha7>, not a commit of this item` (for instance HEAD in a shared worktree
now pointing at another item's commit).

**Red-proofs themselves are run by you, the orchestrator**, not the helper — `verify` only
returns the checklist (`items[].redProof.shape`, plus `items[].redProof.commands` for whichever
of the project's `run-profile.json` → `verify[]` entries name the `orchestrator` seat) of what to
run; this repo's profile names the lock helper self-check and a scratch-worktree
revert-and-rebuild. A `verify` failure (`items[].ok: false`, non-empty `findings`) on any item
means that item **fails this run** — do not advance it in Step 4 below, and surface the specific
failure (missing SHA, foreign file, missing trailer, failed red-proof) rather than a generic
"verify failed".

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
   `node "<helper>" provenance --plan <plan> --result <result> --method A|B --turns <n> [--usage <usage-file>] [--meta-dir <dir>]`. Pass `--meta-dir` when per-seat `*.meta.json` files with
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

**Security-assessment items.** The review-prompt helper does not know the item's schema. When the item's resolved schema has a `security-assessment` note (check `get_context` expectedNotes or the plan's `review.requiredReviewNotes`), append to the generated review prompt a line naming the worktree path beside the diff command, plus: "Apply the /security-review method to this item's owned-file diff in its worktree — `git -C <worktree> diff <baseSha>..HEAD -- <owned files>` — not by invoking the built-in command, which diffs the session cwd and is empty for a worktree branch."

**Fix cycle after a Fail.** When a review returns Fail, run the same four seats again, in order,
each scoped to one numbered amendment `A<n>` (A1 for the first Fail, A2 for the next). There is no
cap on cycles.

1. **Planner amends, by name.** Resume the item's planner seat with the blocking findings (via
   `SendMessage`). It appends a section `## Amendment A<n>` to its own planning note
   (`specification` for plugin-change, `task-scope`/`diagnosis` where the schema carries those) —
   never a rewrite of the frozen body above it, and never a separate note key, so the independence
   audit can still check that the note's freeze timestamp (`modifiedAt`) predates the fix commit.
   The amendment lists every existing test file it expects edited (planner-v1 `existingTestEdits`)
   and the mutation red-proofs the fix must satisfy. Keep amendments terse: accumulated ones made a
   50k `task-scope` in one run.
2. **Implementer, then blind test author, by name**, each scoped to section `A<n>` only. The test
   author appends its own `A<n>` section to `test-plan`/`test-manifest`; the planner does not touch
   those keys, which the test-author seat owns.
3. **Orchestrator runs the amendment's named mutation red-proofs** in a scratch copy, never the
   shared tree (same rule as Step 1).
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

---

## Step 7 — Close and loop

Once the review hand-off (Step 5) is dispatched and any immediate human checkpoints (Step 6) are
surfaced, set `state.phase = "closed"`. Report, in one place, every item that was **deferred**
(cross-run edge, resource contention, run-size cap — with reasons), every item that was
**stopped** (invalid envelope, verify/actor-audit failure), and every item that was **excluded**
at planning time (unowned required note, unavailable schema config) — each with its specific
reason, not a bare count. Then return to `SKILL.md` Step 9: re-run Step 2 (a fresh snapshot),
letting the items surfaced in Step 4's `unblockedItems` join the new frontier.

A `closed` run's state document and pointer line are left in place (`manage_plan_documents` has
no delete) — Step 0 F7's `phase != "closed"` filter is what keeps a finished run from being
mistaken for an open one on the next invocation.
