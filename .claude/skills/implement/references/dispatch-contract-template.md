# Dispatch contract template

Copy everything below the horizontal rule into the wave's plan file at `/implement` `wave.md` Step 2 for
every shared wave, fill every `<placeholder>`, and reference the result by
its **absolute path** from every hand-dispatch and review prompt.

- **Where the file lives.** `plans/` is gitignored, so the plan file exists only in the MAIN
  checkout and never appears in the feature worktree an agent is pointed at. Write it to
  `<main-checkout>\plans\<slug>.md` and hand agents that absolute path — a relative `plans/<slug>.md`
  resolves against the agent's working directory (the worktree) and does not exist there. The
  Header's **Contract path** line is where that absolute path is recorded once.
- **The slots that carry rules are copied verbatim** — Conflict rule, Commit discipline, Compile
  self-check, Test author protocol, Contract-change sweep, Review scoping. Only the
  `<placeholders>` inside them change. Do not paraphrase them into a dispatch prompt; point the
  agent at the slot instead.
- **Delete no slot.** A slot that does not apply is filled with `n/a — <why>`, so a reader can
  tell a decision from an omission.
- **Slot order is fixed** so an agent told "see the Compile self-check slot" finds it without
  reading the whole file: Header · Conflict rule · Items · Planning seat return template ·
  Commit discipline · Compile self-check · File ownership · Test author protocol ·
  Contract-change sweep · Docs · Notes · Review scoping.

The Review scoping slot's commit map and the per-item bug-fix revert results are filled as the wave
runs, not at Step 2; the budget-mutation results land after the feature PR merges (the post-merge
red-proofs, `/implement` "After merge"). Every other slot is complete before the first dispatch.

Each adopted proposal's `adoption-decision` note names where its change landed.

---

# <Wave or feature title> — dispatch contract

Feature branch: `feat/<slug>`
Feature worktree: `<absolute path, e.g. D:\Projects\task-orchestrator\.claude\worktrees\feat-<slug>>`
Write root: `<the feature worktree path above>` — every Write/Edit path starts with it. Main-checkout
paths are off-limits (this contract and `plans/` are read-only inputs). Before your first commit, run
`git -C <write root> status --short` AND `git -C <main-checkout> status --short` and report both in
`implementation-notes`; the main checkout must show nothing you wrote. File-edit method, only when
Edit/Write is refused (the session-worktree guard): `.claude/skills/implement/references/patch-anchored.py`
with a JSON edit spec, run with `DRY=1` first — its anchors follow the File ownership slot's
exact-text-anchor rule. Never `sed -i`, and never build a path from an f-string or string
concatenation: spell every path out under the write root.
Contract path (this file): `<absolute path in the MAIN checkout, e.g. D:\Projects\task-orchestrator\plans\<slug>.md>`
— every dispatch prompt names the contract by THIS path. `plans/` is gitignored and absent from
the feature worktree, so a relative `plans/<slug>.md` does not resolve for an agent whose working
directory is the worktree.
Base: `<short-sha>` (origin/main at <date>, after PR #<n>)
PR: ONE PR for the planned scope (every wave of the parent), opened when every item is terminal.
Scratchpad: `<absolute scratchpad path from the session environment>`
Lock helper (agents' ONLY gradle entry point): `<worktree>\.claude\skills\implement\references\gradle-locked.ps1`
(the tracked file, referenced in place; never copied into the scratchpad and never hand-written per
wave; the scratchpad is for logs only). It refuses `:current:test`, `:current:ktlintCheck` and
`--rerun-tasks` (EXIT=98) unless `TO_GRADLE_OWNER=orchestrator` is set in the calling process; only the orchestrator's own
invocations set it, inline, and no dispatch prompt ever does.

## Conflict rule

This file wins on conflict with any dispatch prompt. The per-item `<note key, e.g. test-plan>`
MCP note wins on conflict with this file for `<the dimension that note owns — e.g. scenarios,
oracles, and the public signatures tests compile against>`.

## Items

| Item | Title | Decision / scope anchor | State matrix |
|---|---|---|---|
| `<short-uuid>` | `<title>` | `<the scope decision already made — what layer the fix lives at, what stays from an earlier wave, what an agent may not re-litigate>` | `<the spec note key holding the matrix (task-scope, diagnosis or specification), or n/a - <reason>>` |

## Planning seat return template

One `planning`-class agent (`task-orchestrator:model-effort`) per stream returns this block verbatim (field names fixed; `none` is a valid
value for any field except `main-files`/`test-files`, and `defect-class-siblings` may say `none`
only with the sweep that found none). Field semantics: the table below the block. Under run-wave the
`planner-v1` stage returns its own schema-checked shape instead, and its generated prompt does not
read this slot.

Seat token (planning seat): `<path, or "none (hand-dispatch outside a run)">`, handled per rule `protocol.in-phase-seat` `claude:`.

```
<short-uuid>:
diagnosis-corrections: <file:line corrections to diagnosis/task-scope, or none>
defect-class-siblings: <each sibling site of the root-cause pattern → frozen as D# | deferred: <reason>; sweep: <command + scope + hit count> — or none (sweep: <command>)>
cross-stream-file-overlaps: <files also owned by another stream in this wave, or none>
missing-api-or-seam: <proposed NEW signature for a missing surface, or none>
test-plan-status: <filled (<n> chars) | open — and why>
main-files: <comma list>
test-files: <comma list of NEW test files>
red-proof-shape: <the item's red-proof budget (test-author rule section 5): bug-fix revert | up to ~5 decision-point mutations, each EXISTING-SURFACE | NEW-SURFACE + narrowest-revert recipe | no behavioural red possible, reviewer verifies <X> | skip: <category>>
```

The fields are the budget: the return may exceed any line budget when the fields have content, and
trimming a field to fit a target length is the failure this seat exists to prevent.

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

## Commit discipline

Fetch rule `commit-discipline` with `docs_read` `get` (`rootId` the project root, `kind` `rule`, `slug` `commit-discipline`)
for the full rule. Inline here only what the rule cannot supply — the wave-specific form:

- `<wt>` = the feature worktree path (Header, above).
- Commit subject form: `<type>(<scope>): <title> [<short-uuid>]`.
- Trailer line: `Co-Authored-By: <the dispatched agent's model attribution line>` — its own `-m`,
  not folded into the summary or the why line.

## Compile self-check

Run ONCE, via the lock helper, before committing:

```
& "<feature worktree>\.claude\skills\implement\references\gradle-locked.ps1" -Worktree "<feature worktree>" -Tasks ":current:compileKotlin",":current:compileTestKotlin"
```

- Run it from the PowerShell tool with the call operator (`&`) exactly as written above — one
  process, `-Tasks` as a comma-separated ARRAY LITERAL of quoted strings. Do NOT wrap it in a
  nested `powershell -File ...`: the child process flattens the `[string[]]` parameter into one
  string (gradle reports `project 'compileKotlin,' not found`) and the run is lost.
- **Where `EXIT` comes from:** the lock helper prints `EXIT=<n>` as its final line and exits with
  that same code, so the value is read off the helper's own output — nothing else to capture.
  Never pipe the helper (or any gradle invocation) through `tail`/`head`: the pipeline reports the
  pager's exit code, not gradle's, which is how a red build reads as green (proposal `64be7e4b`).
  Where an invocation's output must be shortened, redirect first and branch on the saved code:
  `<invocation> > "<scratchpad>\<name>.log" 2>&1; EXIT=$?` then read the log file.
- `EXIT=0` → commit.
- `EXIT≠0` and EVERY error names a file outside your owned list → record the foreign file(s) and
  the error text under `Not done:` in `implementation-notes` and in your return (that note has no
  Friction section), commit anyway, and return. Do **not**
  retry: the file belongs to another agent mid-flight and will change under you.
- `EXIT≠0` with at least one error in a file you own → fix and re-run, **max 3 invocations
  total**. Still red on your own files: commit nothing, report the error verbatim, return.
- Lint is not yours: the orchestrator's `wave-build` runs `ktlintCheck` once for the wave and fixes what it finds.
- **Report the classification you just made**, in the return line's `compile:` field: `EXIT=0`, or
  `EXIT=<n> (foreign-only)`, or `EXIT=<n> (own-file-errors: <files>)`. A bare `EXIT=1` reads as
  routine foreign noise to the orchestrator; an own-file failure reported that way surfaces only
  at the full-suite run (proposal `b82537e4`).

**Who runs gradle in this wave.** Rule `test-author` section 5 points 10-11 decide who runs what
and when; this table keeps this repo's mechanics (lock helper, `-TestFiles`, `TO_GRADLE_OWNER`).

| Seat | Gradle it runs |
|---|---|
| Implementer | The two pinned compile tasks above, ONCE, through the lock helper. Plus, at most once more, a SCOPED run of the test classes in the packages it changed: `-TestFiles "<those test files>"` (the helper maps them to `--tests`). Never bare `:current:test`, never `:current:ktlintCheck`, never `--rerun-tasks` — the helper refuses them. |
| Test author | The two pinned compile tasks above, ONCE, through the lock helper. Plus scoped runs of ITS OWN test files (`-TestFiles`, the run plan's `<ownedTests>`) to see them red and then green. The same three nevers; a `VERIFY` line in `test-manifest` that cites a whole-suite count is a contract breach, not evidence. |
| Hand-dispatched post-run seats (fix implementer, `<seat>-a<n>`, a second test author, a docs seat) | Exactly their base seat's row. Being dispatched outside the run plan does not lift the rule; the prompt points at this table like any other. |
| Orchestrator | `:current:test` and `:current:ktlintCheck` through the lock helper, with `TO_GRADLE_OWNER=orchestrator` set inline on that call. `--rerun-tasks` only on the run after a dependency or Gradle upgrade, never routinely. |
| Reviewer | None. The build state reviewers rely on is recorded in the Review scoping slot; a reviewer who wants a number reads it there or asks the orchestrator. A doubted red-proof result is re-run scoped (`-TestFiles`), never as the whole suite. |

## File ownership

One row per stream. The listed files are that agent's ENTIRE writable scope; anything absent
belongs to another agent or to the orchestrator. **Impl model** is not a free choice for a
seat-aware item: copy model and effort from the item's resolved `dispatchBySeat` (implementer seat),
else the `implementation` class, and change them by applying a model-selection trait (rule
`trait-selection`), never by editing this column alone.

| Stream | Item | Impl model | Production files owned (src/main unless noted) | New test files (author-owned) | Declared edits to EXISTING test files (author-owned) |
|---|---|---|---|---|---|
| `<stream>` | `<short-uuid>` | `<model>/<effort>` | `<path (the specific change, so a reader can tell scope from a filename)>` | `<path>` | `<path + the exact edit, e.g. "IdempotencyToolsTest.kt — delete the superseded swallow-asserting test at ~615-650 only" — or "none">` |

The last column is the ONLY way an author may touch a file it did not create: a contract change
that invalidates an existing test is declared here, named down to the edit, before dispatch —
never discovered and performed mid-wave. An undeclared edit to an existing test file is a scope
breach under rule `review-scoping` point 4 and rule `test-author` section 7, and its absence from
this column is what makes that rule checkable. Anything listed here is also declared in the Review scoping commit map.

**Shared files: re-read immediately before editing.** Before editing a file you share ownership
of with another stream in this wave, re-read it immediately before the edit — a sibling's commit
can shift line numbers or rename or remove a heading you cached from an earlier read. Anchor every
edit on exact text (`old_string`), never on line numbers; an instruction that locates a change
for an agent quotes the text, not a line range.

Declared cross-stream overlaps: `<file → the streams that both write, and the serialization or
arbitration rule that applies ("lock-serialized, no BLOCKS edge" for a file shared in one worktree) — or "none (explicitly verified by all planners)">`.
Untouched by implementers: `<files no stream may write — wiring/entry points, shared harnesses,
and all docs>`.
Scope decisions (orchestrator): `<in/out-of-scope calls a reader would otherwise re-litigate,
each with where the excluded work goes instead>`.

## Test author protocol

One author per item, `src/test/**` only, dispatched after that item's implementer returns. Fetch
rules `test-author`, `forbidden-test-patterns`, `test-assertion-vacuity`, `test-harness.full-wiring`,
and `declarations-extractor` (each with `docs_read` `get`, `kind` `rule`, `slug` the rule name) for the
full protocol — blindness, oracle-derivation, scope, forbidden patterns, harness wiring, and the
declarations-extractor seat's accuracy contract and orchestrator scan all live there now. Inline
here only what those rules cannot supply — the wave-specific parameters:

**Declarations file:** `<path the declarations-extractor seat writes to for this item>`.

```
DECLARATIONS for <short-uuid> — verbatim and complete
<every public declaration the tests touch: types and data-class constructors with full parameter
lists and defaults; function and method signatures; constants; enum values; and any KDoc carrying
an oracle or stating an invariant — including the validate() the fixtures must satisfy>
harness: <fully-qualified src/test helper fn + file:line | NONE + the exact plugin list production installs>
runtime call order: <MCP: the pinned sentence below | REST: the route's check order from the frozen error table>
```

`keys=` filter for the author's `notes_read` calls: the item's queue-phase keys (`items_read`
`schema`), never the seat's `reads_exclude` keys.

Seat token (test author): `<path, or "none (hand-dispatch outside a run)">`, handled per rule `protocol.in-phase-seat` `claude:`.

Return line (rule `test-author` `claude:`): `<short-uuid>: commit <sha> | files: <list> | scenarios: <covered>/<total> | compile: EXIT=<n> [(foreign-only) | (own-file-errors: <files>)] | missing-declaration: <none or name>`

## Contract-change sweep

Fetch rule `contract-change-sweep` with `docs_read` `get` (`rootId` the project root, `kind` `rule`, `slug` `contract-change-sweep`)
for the full rule — timing, what counts as tightening, the sweep steps, and the construction-only
repair discipline all live there now. Inline here only the wave-specific fact:

Tightening changes this wave: `<per item — the tightened contract, or "none">`.

## Docs

Implementers do not edit `current/docs/**`, `README.md`, `CLAUDE.md` or `CHANGELOG.md`. List the
exact edits your change requires under `Not done:` ("Docs needed") in `implementation-notes`; a single
serialized docs seat makes them after the implementation wave, so two agents never write the same
doc. The `CHANGELOG.md` `[Unreleased]` bullet belongs to the orchestrator or the docs seat, written
once after the implementation wave and before review (`/implement` `wave.md` Step 4c), never to an implementer: parallel streams appending under the
same `### Changed` heading collide on one shared anchor.

Any CHANGELOG or doc line the docs seat writes that states nullability, redaction, ownership or an
honest limit carries the `file:line` that establishes it; the reviewer opens each one.

Docs seat for this wave: `<agent/seat, or "orchestrator">`.

## Notes

Generated from the run plan (`stages[].notes` + `orchestratorNotes`).

| Seat | Note keys it fills | maxLength |
|---|---|---|
| Implementer | `implementation-notes` | `<3000>` |
| Test author | `test-manifest` | `<3000>` |
| Reviewer | `review-checklist`, `test-independence-audit` | `<1500 / …>` |
| Orchestrator | `delegation-metadata`, `session-tracking` | `<... / 2000>` |

On a run member the orchestrator writes `delegation-metadata` and `session-tracking` under the
coordinator token, final content written once in confirmed past tense (the only re-edits are the ones
run-wave `references/post-run.md` Step 3 sanctions, the run-wave pointer line in `run-pointers` among them), copying the friction the implementer reported
(its `Not done:` entries and return) into `session-tracking` `Friction:`; seats never write them.

Take every limit and target from the item's RESOLVED schema (`items_read` `schema`,
`itemId`, `fields: ["guidance"]` - each entry's `maxLength` and guidance text), not from this table and not
from memory. Where this table names a number, it is a convenience copy — the schema wins on conflict.

`note_limits.mode` is `warn` unless the effective config (per-root or global) sets `reject`. Under `warn` an over-limit note is
ACCEPTED and returns a `warning` field — a warning is not a failure and is not a reason to
re-upsert. Under `reject` the note element fails `note_too_long` and must be shortened
(`notes_write` `upsert`; `manage-schemas` `references/config-format.md` → "Note Body Length Limits").

Only the run plan's entry seat calls `advance` `transition` with `trigger: "start"`; the
orchestrator owns every later transition.

Seat token (every seat that writes notes): `<path, or "none (hand-dispatch outside a run)">`, handled per rule `protocol.in-phase-seat` `claude:`.

## Review scoping

Fetch rule `review-scoping` with `docs_read` `get` (`rootId` the project root, `kind` `rule`, `slug` `review-scoping`)
for the full rule — the owned-files-not-SHA-range diff and the two-range ownership check both live
there now. The map below is the wave-specific record that rule's step 3 requires.

| Item | Implementer commits (src/main) | Author commits (src/test) | Declared notes |
|---|---|---|---|
| `<short-uuid>` | `<shas>` | `<shas>` | `<arbitrations, declared contract changes, declared edits to existing test files, independence caveats — or "—">` |
| docs (all items) | `<docs-seat shas>` | — | `<the serialized docs pass; no item owns these files>` |
| sweep / fixture repairs | `<orchestrator shas>` | — | `<which contract tightening each repair answers — or "none">` |

The last two rows keep every commit on the branch accounted for: a reviewer diffing owned files
would otherwise meet docs and fixture-repair commits with no owner and read them as a boundary
violation.

Build state reviewers rely on: `Build state: <:current:test and :current:ktlintCheck> green|red at <sha>` (no tally; the orchestrator's one wave run). Reviewers run no gradle — see the Compile self-check slot's "Who runs gradle" table.

Seat token (reviewer): `<path, or "none (hand-dispatch outside a run)">`, handled per rule `protocol.read-only-agent` `claude:`.
Pre-merge red-proof results (orchestrator-run in a scratch worktree, item tests only: the bug-fix
revert and any coupled-authorship checks; every revert applied with
`.claude/skills/implement/references/patch-anchored.py`, spec written with the Write tool, `DRY=1` first):
`<per item - "revert red / fix green", or "n/a: budget mutations deferred to the post-merge red-proofs", or "skip: <category>">`.
Post-merge red-proof results (run per rule `test-author` section 5 and `/implement` "After
merge"), on the wave parent's `red-proof-results` note, per item: `<proofs run, gaps found,
equivalents, approx minutes>`.

Red-proof run rules (every red-proof run and any red-proof runner prompt; proposal `4ba93956`):
1. Expected durations: the full Kotlin suite takes about 6-7 minutes on 4.0; a targeted class run
   (`--tests`) about 1 minute.
2. Beyond 2x the expected time, take a `jstack` of the test worker, kill only that worker (a
   process this seat started, never another seat's or the orchestrator's), and report HUNG with the
   top frames.
3. The scratch red-proof worktree uses its own `--project-cache-dir`, and the runner runs
   `gradlew --stop` for its own daemon before returning.
4. Tests touching run loops or blocking IO carry `@Timeout`.
