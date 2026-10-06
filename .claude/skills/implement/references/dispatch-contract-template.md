# Dispatch contract template

Copy everything below the horizontal rule into the wave's plan file at Step 2 of `/implement` for
every Parallel-tier run (3+ children), fill every `<placeholder>`, and reference the result by
its **absolute path** from every Step 3, 4, 4b and 5 dispatch prompt.

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

The Review scoping slot's commit map and the per-item red-proof results are filled as the wave
runs, not at Step 2. Every other slot is complete before the first dispatch.

## Adoption reach

Each proposal below maps to the slot that now carries its adopted fix, or is declared out of
scope with the reason. Adoption test: a retrospective checks recurrence of an adopted rule's
failure class against whether the rule occupies a slot in this file — not against whether the
proposal item's own status says "terminal".

| Proposal | Topic | Slot / disposition |
|---|---|---|
| `82034e9a` | contract-change sweep checklist | **Contract-change sweep** — moved to rule `contract-change-sweep`; the post-wave sweep of call sites and fixtures after any contract-tightening change, and its construction-only repair rule |
| `31a1abeb` | RepositoryProvider strict-mock breakage | **Contract-change sweep** — moved to rule `contract-change-sweep`; a new accessor on a strict-mocked interface is a contract tightening; the sweep's grep-and-repair step and its declaration in the commit map are the delivery |
| `4c25e3c7` | measure automated-budget headroom during planning | out of scope: adopted in `spec-quality/SKILL.md`, which queue-phase note `guidance` pointers route agents to at fill time — a delivery surface of its own that fires before this file exists for a wave, not skill prose an agent may never open |
| `ee6f5d32` | add `ktlintFormat` to the agent compile self-check | **Compile self-check** — `:current:ktlintFormat` is first in the pinned task list |
| `64be7e4b` | explicit-exit-code gradle pattern | **Compile self-check** — the `EXIT=0`/`EXIT≠0` branching; the proposal's `--rerun-tasks` clause stays an orchestrator-side habit, not a delegated fact |
| `a8c08a0d` | finalize closure notes only after confirmed merge/advance | out of scope: an orchestrator closeout-sequencing rule (merge-check → advance → note), not an agent dispatch fact |
| `568e7f7e` (#301) | `git commit --only` + owned-file-diff review scoping | **Commit discipline** and **Review scoping** — moved to rules `commit-discipline` and `review-scoping` |
| `b82537e4` (#302) | early exit on unowned-file compile errors + pinned build invocation | **Compile self-check** — the foreign-file early-exit rule and the array-literal `-Tasks` invocation |
| `4cef371c` (#303) | fixtures must satisfy domain `validate()` | **Test author protocol** — moved to rule `test-author` §7, "Fixture invariants" |
| `98d0a3b2` (#304) | planning seat as a named stage + structured return | **Planning seat return template** |
| `fc55c183` (#306) | EXISTING-SURFACE/NEW-SURFACE labels + red-proof-shape | **Planning seat return template** (`red-proof-shape` field) and **Test author protocol** — moved to rule `test-author` §2, "Surface labels" |
| `728a3e57` (#307) | deliver adopted rules through the dispatch contract, not skill prose | this document — the template's existence plus this Adoption reach table is the shipped fix |
| `7e9b37bb` (#310) | test-author blindness as a structural, tool-barred boundary | **Test author protocol** — moved to rule `test-author` §4 (declarations block, hard `src/main` ban, mandatory `keys=`, stop-and-ask) |
| `a85d2b5d` (#312) | re-read shared files before editing; exact-text anchors | **File ownership** — "Shared files: re-read immediately before editing" |
| `82ca5395` | assertions that cannot fail given their fixture or harness | **Test author protocol** — moved to rules `forbidden-test-patterns` and `test-assertion-vacuity`; the vacuity, harness-transformation and rejection-reason checks live there and in `test-author/SKILL.md` §7 |
| `d1484a3e` | worktree writes landing in the main checkout | **Header** — the "Write root" line and the two-checkout `git status --short` before the first commit |
| `234b50a0` | declarations-extractor seat and its accuracy contract | **Test author protocol** — moved to rule `declarations-extractor` (extractor seat, accuracy contract, orchestrator scan) and rule `test-author` §4's public-evidence self-resolution clause |
| `b5bc3210` (#396) | cite a source line for every security or edge-case claim | **Docs** slot (doc lines carry file:line) plus `spec-quality/SKILL.md` ("Semantic claims carry a file:line"), `review-quality/SKILL.md` (Area 2 citation check, Fail list) and the `planner`/`reviewer` agent definitions |
| `bb191508` | sweep the whole defect class during planning | **Planning seat return template** (`defect-class-siblings` field); the `bug-fix` schema's `diagnosis` guidance carries the same requirement at note-fill time |
| `c068c943` (#360) | per-PR CHANGELOG `[Unreleased]` bullet | **Docs** — `CHANGELOG.md` is orchestrator- or docs-seat-owned, written before review, never an implementer's; the bullet itself (`/implement` Step 4c) and the Step 6 check plus the PR body's `## Changelog` section are orchestrator-side steps, not delegated facts |
| `27c021b1` (#318) | hand-assembled Ktor test apps diverging from production | **Test author protocol** — moved to rules `declarations-extractor` (the declarations block's `harness:` line and its accuracy-contract bullet) and `test-harness.full-wiring` (the named-helper-or-stop-and-ask clause) |
| `395f316f` (#374) | tests that bypass the runtime's validation order | **Test author protocol** — moved to rule `declarations-extractor` (the declarations block's `runtime call order:` line and its accuracy-contract bullet, the pinned `McpToolAdapter` sentence) |
| `9023ed46` (#361) | unscoped dedup search before creating a root or feature item | out of scope: an item-creation step, adopted in the plugin skills `post-plan-workflow` (Phase 1) and `create-item` (Step 5), which run where items are materialized — no dispatched seat creates items |
| `610b9a9f` (#373) | anchored byte-level edits when Edit/Write is refused | **Header** — the conditional "File-edit method" clause on the Write root line, pointing at `references/patch-anchored.py` (exact-once anchors, `DRY=1`, validate-all-then-write, per-file CRLF/LF preserved) |
| `c71ff3cb` (#356) | subagents leaving background commands running after they return | out of scope: delivered where the phase-owner seats already read it — the SubagentStart hook's Subagent Discipline item 4 (phase-owner seats), the `implementer`/`reviewer` agent definitions (plain dispatches), and the `implement-wave`/`review-wave` workflow prompts |
| `aacfc16b` (#336) | multi-row empirical probe for runtime-dependent at-rest/security guarantees | out of scope: adopted in `.claude/skills/migration-review/SKILL.md` Step 3, which the `needs-migration-review` trait's `migration-assessment` note routes the planning seat to at fill time |
| `ef33c175` (#319) | negative and exhaustive claims must name their sweep | **Contract-change sweep** — moved to rule `contract-change-sweep` §3 (a planner's negative claim does not discharge the sweep); the general rule lives in `spec-quality/SKILL.md` → Blast Radius, and the `defect-class-siblings` field (`bb191508`) is its planning-seat special case |
| `4048a4ee` (#320) | note length: schema-sourced targets and `note_limits` warn/reject semantics | **Notes** — the table's numbers are a convenience copy the resolved schema overrides, and `warn` accepts an over-limit note with a `warning` (no re-upsert) while `reject` fails it with `NOTE_BODY_TOO_LONG` |

---

# <Wave or feature title> — dispatch contract

Feature branch: `feat/<slug>`
Feature worktree: `<absolute path, e.g. D:\Projects\task-orchestrator\.claude\worktrees\feat-<slug>>`
Write root: `<the feature worktree path above>` — every Write/Edit path starts with it. Main-checkout
paths are off-limits (this contract and `plans/` are read-only inputs). Before your first commit, run
`git -C <write root> status --short` AND `git -C <main-checkout> status --short` and report both in
`session-tracking`; the main checkout must show nothing you wrote. File-edit method, only when
Edit/Write is refused (the session-worktree guard): `.claude/skills/implement/references/patch-anchored.py`
with a JSON edit spec, run with `DRY=1` first — its anchors follow the File ownership slot's
exact-text-anchor rule. Never `sed -i`, and never build a path from an f-string or string
concatenation: spell every path out under the write root.
Contract path (this file): `<absolute path in the MAIN checkout, e.g. D:\Projects\task-orchestrator\plans\<slug>.md>`
— every dispatch prompt names the contract by THIS path. `plans/` is gitignored and absent from
the feature worktree, so a relative `plans/<slug>.md` does not resolve for an agent whose working
directory is the worktree.
Base: `<short-sha>` (origin/main at <date>, after PR #<n>)
PR boundary: ONE PR for the wave, opened when every item is terminal.
Scratchpad: `<absolute scratchpad path from the session environment>`
Lock helper (agents' ONLY gradle entry point): `<scratchpad>\gradle-locked.ps1`

## Conflict rule

This file wins on conflict with any dispatch prompt. The per-item `<note key, e.g. test-plan>`
MCP note wins on conflict with this file for `<the dimension that note owns — e.g. scenarios,
oracles, and the public signatures tests compile against>`.

## Items

| Item | Title | Decision / scope anchor |
|---|---|---|
| `<short-uuid>` | `<title>` | `<the scope decision already made — what layer the fix lives at, what stays from an earlier wave, what an agent may not re-litigate>` |

## Planning seat return template

One `opus` agent per stream returns this block verbatim (field names fixed; `none` is a valid
value for any field except `main-files`/`test-files`, and `defect-class-siblings` may say `none`
only with the sweep that found none). Field semantics: `/implement` SKILL.md
Step 3, "Planning seat" subsection — prose describes, this states.

```
<short-uuid>:
diagnosis-corrections: <file:line corrections to diagnosis/task-scope, or none>
defect-class-siblings: <each sibling site of the root-cause pattern → frozen as D# | deferred: <reason>; sweep: <command + scope + hit count> — or none (sweep: <command>)>
cross-stream-file-overlaps: <files also owned by another stream in this wave, or none>
missing-api-or-seam: <proposed NEW signature for a missing surface, or none>
test-plan-status: <filled (<n> chars) | open — and why>
main-files: <comma list>
test-files: <comma list of NEW test files>
red-proof-shape: <per scenario — EXISTING-SURFACE | NEW-SURFACE + narrowest-revert recipe | no behavioural red possible, reviewer verifies <X>>
```

## Commit discipline

Fetch rule `commit-discipline` (`query_rules(operation="get", rootId=<rootId>, key="commit-discipline")`)
for the full rule. Inline here only what the rule cannot supply — the wave-specific form:

- `<wt>` = the feature worktree path (Header, above).
- Commit subject form: `<type>(<scope>): <title> [<short-uuid>]`.
- Trailer line: `Co-Authored-By: <the dispatched agent's model attribution line>` — its own `-m`,
  not folded into the summary or the why line.

## Compile self-check

Run ONCE, via the lock helper, before committing:

```
& "<scratchpad>\gradle-locked.ps1" -Worktree "<feature worktree>" -Tasks ":current:ktlintFormat",":current:compileKotlin",":current:compileTestKotlin"
```

- Run it from the PowerShell tool with the call operator (`&`) exactly as written above — one
  process, `-Tasks` as a comma-separated ARRAY LITERAL of quoted strings. Do NOT wrap it in a
  nested `powershell -File ...`: the child process flattens the `[string[]]` parameter into one
  string (gradle reports `project 'ktlintFormat,' not found`) and the run is lost.
- **Where `EXIT` comes from:** the lock helper prints `EXIT=<n>` as its final line and exits with
  that same code, so the value is read off the helper's own output — nothing else to capture.
  Never pipe the helper (or any gradle invocation) through `tail`/`head`: the pipeline reports the
  pager's exit code, not gradle's, which is how a red build reads as green (proposal `64be7e4b`).
  Where an invocation's output must be shortened, redirect first and branch on the saved code:
  `<invocation> > "<scratchpad>\<name>.log" 2>&1; EXIT=$?` then read the log file.
- `EXIT=0` → commit.
- `EXIT≠0` and EVERY error names a file outside your owned list → record the foreign file(s) and
  the error text under **Friction** in `session-tracking`, commit anyway, and return. Do **not**
  retry: the file belongs to another agent mid-flight and will change under you.
- `EXIT≠0` with at least one error in a file you own → fix and re-run, **max 3 invocations
  total**. Still red on your own files: commit nothing, report the error verbatim, return.
- `:current:ktlintFormat` may reformat files you do not own. Never stage those.
- **Report the classification you just made**, in the return line's `compile:` field: `EXIT=0`, or
  `EXIT=<n> (foreign-only)`, or `EXIT=<n> (own-file-errors: <files>)`. A bare `EXIT=1` reads as
  routine foreign noise to the orchestrator; an own-file failure reported that way surfaces only
  at the full-suite run (proposal `b82537e4`).

**Who runs gradle in this wave** (the single statement; every other slot, prompt and skill step
points here instead of restating it):

| Seat | Gradle it runs |
|---|---|
| Implementer, test author | ONLY the three pinned tasks above, ONCE, through the lock helper. Never `:current:test`, never `:current:ktlintCheck`. |
| Orchestrator | `:current:test` and `:current:ktlintCheck` for the whole wave, serialized, plus the red-proof reverts. |
| Reviewer | None. The build state reviewers rely on is recorded in the Review scoping slot; a reviewer who wants a number reads it there or asks the orchestrator. |

## File ownership

One row per stream. The listed files are that agent's ENTIRE writable scope; anything absent
belongs to another agent or to the orchestrator. **Impl model** is not a free choice for a
seat-aware item: copy it from the item's resolved `dispatchBySeat` (implementer seat), and change
it by applying a model-selection trait (`/implement` Step 1), never by editing this column alone.

| Stream | Item | Impl model | Production files owned (src/main unless noted) | New test files (author-owned) | Declared edits to EXISTING test files (author-owned) |
|---|---|---|---|---|---|
| `<stream>` | `<short-uuid>` | `<sonnet\|opus>` | `<path (the specific change, so a reader can tell scope from a filename)>` | `<path>` | `<path + the exact edit, e.g. "IdempotencyToolsTest.kt — delete the superseded swallow-asserting test at ~615-650 only" — or "none">` |

The last column is the ONLY way an author may touch a file it did not create: a contract change
that invalidates an existing test is declared here, named down to the edit, before dispatch —
never discovered and performed mid-wave. An undeclared edit to an existing test file is a scope
breach under Test author protocol rule 8, and its absence from this column is what makes that
rule checkable. Anything listed here is also declared in the Review scoping commit map.

**Shared files: re-read immediately before editing.** Before editing a file you share ownership
of with another stream in this wave, re-read it immediately before the edit — a sibling's commit
can shift line numbers or rename or remove a heading you cached from an earlier read. Anchor every
edit on exact text (`old_string`), never on line numbers; an instruction that locates a change
for an agent quotes the text, not a line range.

Declared cross-stream overlaps: `<file → the streams that both write, and the serialization or
arbitration rule that applies — or "none (explicitly verified by all planners)">`.
Untouched by implementers: `<files no stream may write — wiring/entry points, shared harnesses,
and all docs>`.
Scope decisions (orchestrator): `<in/out-of-scope calls a reader would otherwise re-litigate,
each with where the excluded work goes instead>`.

## Test author protocol

One author per item, `src/test/**` only, dispatched after that item's implementer returns. Fetch
rules `test-author`, `forbidden-test-patterns`, `test-assertion-vacuity`, `test-harness.full-wiring`,
and `declarations-extractor` (`query_rules(operation="get", rootId=<rootId>, key=<key>)`) for the
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

`readsExclude` keys (the queue-phase keys the author's `query_notes` calls are restricted to):
`["task-scope","diagnosis","test-plan"]`.

Return line (rule 13): `<short-uuid>: commit <sha> | files: <list> | scenarios: <covered>/<total> | compile: EXIT=<n> [(foreign-only) | (own-file-errors: <files>)] | missing-declaration: <none or name>`

## Contract-change sweep

Fetch rule `contract-change-sweep` (`query_rules(operation="get", rootId=<rootId>, key="contract-change-sweep")`)
for the full rule — timing, what counts as tightening, the sweep steps, and the construction-only
repair discipline all live there now. Inline here only the wave-specific fact:

Tightening changes this wave: `<per item — the tightened contract, or "none">`.

## Docs

Implementers do not edit `current/docs/**`, `README.md`, `CLAUDE.md` or `CHANGELOG.md`. List the
exact edits your change requires under "Docs needed" in `implementation-notes`; a single
serialized docs seat makes them after the implementation wave, so two agents never write the same
doc. The `CHANGELOG.md` `[Unreleased]` bullet belongs to the orchestrator or the docs seat, written
once after the implementation wave and before review (`/implement` Step 4c), never to an implementer: parallel streams appending under the
same `### Changed` heading collide on one shared anchor.

Any CHANGELOG or doc line the docs seat writes that states nullability, redaction, ownership or an
honest limit carries the `file:line` that establishes it; the reviewer opens each one.

Docs seat for this wave: `<agent/seat, or "orchestrator">`.

## Notes

Generated from the run plan (`stages[].notes` + `orchestratorNotes`).

| Seat | Note keys it fills | maxLength |
|---|---|---|
| Implementer | `implementation-notes`, `session-tracking` | `<3000 / 2000>` |
| Test author | `test-manifest` | `<3000>` |
| Reviewer | `review-checklist`, `test-independence-audit` | `<1500 / …>` |
| Orchestrator | `delegation-metadata` | `<…>` |

Take every limit and target from the item's RESOLVED schema (`query_items(operation="schema",
itemId=...)` — each entry's `maxLength` and guidance), not from this table and not
from memory. Where this table names a number, it is a convenience copy — the schema wins on conflict.

`note_limits.mode` is `warn` unless the effective config (per-root or global) sets `reject`. Under `warn` an over-limit note is
ACCEPTED and returns a `warning` field — a warning is not a failure and is not a reason to
re-upsert. Under `reject` the note fails with `code: NOTE_BODY_TOO_LONG` and must be shortened
(`manage_notes` upsert; `manage-schemas` `references/config-format.md` → "Note Body Length Limits").

Only the run plan's entry seat calls `advance_item(start)`; the
orchestrator owns every later transition.

## Review scoping

Fetch rule `review-scoping` (`query_rules(operation="get", rootId=<rootId>, key="review-scoping")`)
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

Build state reviewers rely on: `<:current:test N tests / 0 failed and :current:ktlintCheck green
at <sha>>`. Reviewers run no gradle — see the Compile self-check slot's "Who runs gradle" table.
Red-proof results (orchestrator-run, fix reverted in a scratch worktree, item tests only; every
mutation or partial revert applied with `.claude/skills/implement/references/patch-anchored.py`,
spec written with the Write tool, `DRY=1` first):
`<per item — "N/M failed", or "compile-red (tests bind to NEW surface <name>)">`.
