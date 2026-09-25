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
| `82034e9a` | contract-change sweep checklist | **Contract-change sweep** — the post-wave sweep of call sites and fixtures after any contract-tightening change, and its construction-only repair rule |
| `31a1abeb` | RepositoryProvider strict-mock breakage | **Contract-change sweep** — a new accessor on a strict-mocked interface is a contract tightening; the sweep's grep-and-repair step and its declaration in the commit map are the delivery |
| `4c25e3c7` | measure automated-budget headroom during planning | out of scope: adopted in `spec-quality/SKILL.md`, which queue-phase note `guidance` pointers route agents to at fill time — a delivery surface of its own that fires before this file exists for a wave, not skill prose an agent may never open |
| `ee6f5d32` | add `ktlintFormat` to the agent compile self-check | **Compile self-check** — `:current:ktlintFormat` is first in the pinned task list |
| `64be7e4b` | explicit-exit-code gradle pattern | **Compile self-check** — the `EXIT=0`/`EXIT≠0` branching; the proposal's `--rerun-tasks` clause stays an orchestrator-side habit, not a delegated fact |
| `a8c08a0d` | finalize closure notes only after confirmed merge/advance | out of scope: an orchestrator closeout-sequencing rule (merge-check → advance → note), not an agent dispatch fact |
| `568e7f7e` (#301) | `git commit --only` + owned-file-diff review scoping | **Commit discipline** and **Review scoping** |
| `b82537e4` (#302) | early exit on unowned-file compile errors + pinned build invocation | **Compile self-check** — the foreign-file early-exit rule and the array-literal `-Tasks` invocation |
| `4cef371c` (#303) | fixtures must satisfy domain `validate()` | **Test author protocol** — rule 6, "Fixture invariants" |
| `98d0a3b2` (#304) | planning seat as a named stage + structured return | **Planning seat return template** |
| `fc55c183` (#306) | EXISTING-SURFACE/NEW-SURFACE labels + red-proof-shape | **Planning seat return template** (`red-proof-shape` field) and **Test author protocol** — rule 5, "Surface labels" |
| `728a3e57` (#307) | deliver adopted rules through the dispatch contract, not skill prose | this document — the template's existence plus this Adoption reach table is the shipped fix |
| `7e9b37bb` (#310) | test-author blindness as a structural, tool-barred boundary | **Test author protocol** — rules 1-4 (declarations block, hard `src/main` ban, mandatory `keys=`, stop-and-ask) |
| `a85d2b5d` (#312) | re-read shared files before editing; exact-text anchors | **File ownership** — "Shared files: re-read immediately before editing" |
| `82ca5395` | assertions that cannot fail given their fixture or harness | **Test author protocol** — rule 9, "Forbidden"; the vacuity, harness-transformation and rejection-reason checks live in `test-author/SKILL.md` §7 |
| `d1484a3e` | worktree writes landing in the main checkout | **Header** — the "Write root" line and the two-checkout `git status --short` before the first commit |
| `234b50a0` | declarations-extractor seat and its accuracy contract | **Test author protocol** — the declarations paragraph (extractor seat, accuracy contract, orchestrator scan) and rule 4's public-evidence self-resolution clause |
| `bb191508` | sweep the whole defect class during planning | **Planning seat return template** (`defect-class-siblings` field); the `bug-fix` schema's `diagnosis` guidance carries the same requirement at note-fill time |

---

# <Wave or feature title> — dispatch contract

Feature branch: `feat/<slug>`
Feature worktree: `<absolute path, e.g. D:\Projects\task-orchestrator\.claude\worktrees\feat-<slug>>`
Write root: `<the feature worktree path above>` — every Write/Edit path starts with it. Main-checkout
paths are off-limits (this contract and `plans/` are read-only inputs). Before your first commit, run
`git -C <write root> status --short` AND `git -C <main-checkout> status --short` and report both in
`session-tracking`; the main checkout must show nothing you wrote.
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

The feature worktree has ONE git index shared by every agent in the wave. `git add` stages into
that shared index, so a bare `git commit` commits whatever any other agent has staged in flight.

- Stage only your own paths: `git -C <wt> add <owned paths>`
- Commit by pathspec, with the attribution trailer as its own `-m`:
  `git -C <wt> commit --only -m "<type>(<scope>): <title> [<short-uuid>]" -m "<why>" -m "Co-Authored-By: <the dispatched agent's model attribution line>" -- <owned paths>`
  The trailer is part of the commit form, not an optional flourish — a wave whose commits drop it
  loses per-seat attribution across the whole PR.
- Verify immediately: `git -C <wt> show --stat HEAD` must list exactly your owned files and
  nothing else.
- On mismatch: `git -C <wt> reset --soft HEAD~1`, re-stage your paths, re-commit with `--only`.
- Never a bare `git commit`. `--only` is the only safe form in a shared worktree.
- `--only` scopes by PATH, not by hunk: everything currently in an owned file is committed,
  including edits you did not make. Read `git -C <wt> diff -- <owned paths>` before committing
  if a formatter has run.
- There is no git equivalent of the gradle lock helper. Path scoping is the entire mechanism.

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

**Who runs gradle in this wave** (the single statement; every other slot, prompt and skill step
points here instead of restating it):

| Seat | Gradle it runs |
|---|---|
| Implementer, test author | ONLY the three pinned tasks above, ONCE, through the lock helper. Never `:current:test`, never `:current:ktlintCheck`. |
| Orchestrator | `:current:test` and `:current:ktlintCheck` for the whole wave, serialized, plus the red-proof reverts. |
| Reviewer | None. The build state reviewers rely on is recorded in the Review scoping slot; a reviewer who wants a number reads it there or asks the orchestrator. |

## File ownership

One row per stream. The listed files are that agent's ENTIRE writable scope; anything absent
belongs to another agent or to the orchestrator.

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

One author per item, `src/test/**` only, dispatched after that item's implementer returns. The
author's blindness is a CAPABILITY boundary, not a reading discipline: it holds only because the
declarations below are supplied and the lookups are banned.

**Declarations — a dedicated declarations-extractor seat (sonnet, read-only, never the item's
author; dispatched after the item's implementer returns) fills one block per item from `src/main`
and the frozen `test-plan`, written to a file. The orchestrator scans it before dispatching the
author. The author looks up nothing.**

```
DECLARATIONS for <short-uuid> — verbatim and complete
<every public declaration the tests touch: types and data-class constructors with full parameter
lists and defaults; function and method signatures; constants; enum values; and any KDoc carrying
an oracle or stating an invariant — including the validate() the fixtures must satisfy>
```

Extractor accuracy contract:

- Copy signatures, constructors with defaults, constants, enum values and oracle-bearing KDoc
  **verbatim** — and only KDoc that pre-dates this item. KDoc the implementation commit added is
  implementation prose; leave it out.
- No prose describing implementation behaviour: never paraphrase what the code does, and never
  paste function bodies, catch blocks or call sites.
- For every scenario input the plan names (route query params, env vars, config keys, tool
  params), give the exact name or write `NOT DECLARED: <what>`.
- A claim that a file or symbol does not exist names the check that produced it.

Orchestrator scan, before handoff: grep the declarations file for behaviour words (`returns`,
`throws`, `falls back`, `catches`, `calls`, `if`, `when`, `otherwise`, `instead`) and strip every
hit that describes behaviour rather than declaring a signature or pre-existing KDoc; then delete
any unredacted copy so only the scanned file reaches the author. The scan is not optional: on
2026-09-25 the extractor leaked implementation prose in 2 of 2 runs despite an explicit
prohibition, and the scan caught both.

Rules for the author (a breach is a breach whether or not anything useful was seen):

1. **Read:** this file; the item's planning notes via
   `query_notes(operation="list", itemId="<uuid>", keys=["task-scope","diagnosis","test-plan"], includeBody=true)`;
   anything under `src/test`. Then invoke the `test-author` skill (Skill tool).
2. **`keys=` is mandatory on EVERY `query_notes` call**, restricted to those queue-phase keys. An
   unfiltered `operation="list"` returns `implementation-notes` and is itself a breach.
3. **Never open any file under `src/main` with ANY tool** — Read, Grep, sed, cat, head, Glob
   preview, editor, or any command whose output includes file content. Never `git diff` / `show` /
   `log -p` on this branch. Not the diff, not `implementation-notes`, not `session-tracking`.
4. **Missing or non-compiling declaration → stop and ask** (`SendMessage` to the orchestrator),
   naming the exact declaration. Do not derive it from context, from a compiler error, or from the
   diff. Asking costs a round-trip; the lookup costs the dispatch. An ambiguity resolvable from
   public non-`src/main` evidence (the tool's parameterSchema, `src/test` harnesses, docs) may be
   self-resolved if the manifest's arbitration record states the evidence used; the reviewer
   verifies it.
5. **Surface labels.** `test-plan` labels every scenario `EXISTING-SURFACE` or `NEW-SURFACE`. Write
   each `NEW-SURFACE` test so the plan's narrowest-revert recipe (keep the new type/parameter,
   revert only its call sites) still exercises it; where the plan names a substitute verification
   instead, carry it into the manifest unchanged.
6. **Fixture invariants.** Satisfy the domain type's `validate()` by construction, deriving
   dependent fields from each other (`claimedAt = claimExpiresAt.minus(ttl)`). Escalate rather than
   relax when a scenario is unconstructible. Never bypass `validate()` by reflection or a test-only
   backdoor.
7. **Oracles** come from the `test-plan` citations, never from what the code returns. Tests must
   fail without the fix; red-proof is orchestrator-run unless this file says otherwise.
8. **Scope:** create ONLY the new test files the File ownership slot lists for your item, plus any
   edit that slot's "Declared edits to EXISTING test files" column names for your item — exactly
   that edit, nothing else in the file. Never `src/main`, never docs, never another item's files,
   never a shared test harness. An existing test that looks wrong but is not declared is reported,
   not edited (rule 4's stop-and-ask path).
9. **Forbidden:** `assumeTrue` on non-platform conditions, `@Disabled` / `@Ignore`, disjunctive
   escapes (`|| isEmpty()`), assert-not-null-only, swallowed exceptions, sleep-until-green, and
   assertions that cannot fail given their fixture or harness (`test-author` §7, "Can this
   assertion fail?").
10. **Compile self-check** ONCE, per the Compile self-check slot — including its "Who runs gradle"
    row for your seat. Foreign-file errors → record, commit, do not retry.
11. **Commit** per the Commit discipline slot:
    `git -C <wt> add <your paths>` then
    `git -C <wt> commit --only -m "test(<scope>): <title> [<short-uuid>]" -m "<why>" -m "Co-Authored-By: <your model attribution line>" -- <your paths>`,
    then `git -C <wt> show --stat HEAD` to verify.
12. **`test-manifest`** (`manage_notes` upsert, role `work`, max `<3000>`): actor id
    `test-author:<short-uuid>`; test file paths; commit SHA range (`<pre>..<post>` across your own
    commits, the form the ownership two-range check and `review-quality`'s independence check both
    read); S-id → test mapping (`covered:
    <method>` / `not-covered: <reason>`); probes run, including the ones that found nothing;
    forbidden-pattern declaration; invariants respected; red-proof shape obtained per
    `NEW-SURFACE` scenario; arbitration record.
13. **Call no `advance_item` and no `manage_items`.** Return ONE line:
    `<short-uuid>: commit <sha> | files: <list> | scenarios: <covered>/<total> | compile: EXIT=<n> | missing-declaration: <none or name>`

On a breach of any rule above, deliberate or accidental: stop, commit nothing, delete any draft
written after it, and disclose exactly what was read — in the return line and in the manifest's
arbitration record. A disclosed breach costs a re-dispatch; an undisclosed one costs the item's
independence verdict.

## Contract-change sweep

Orchestrator-owned, run once after the implementation wave and before build verification. A
change that TIGHTENS a contract breaks call sites and fixtures the changed item never names — a
parameter going optional→required, a new `validate()` invariant, a newly enforced sealed-class
arm, or a new accessor on a widely strict-mocked interface such as `RepositoryProvider`, where
every strict `mockk<…>()` double that does not stub it now fails (proposals `82034e9a`,
`31a1abeb`). Both cost fix-up cycles even in waves whose dispatch prompts warned about it, so the
sweep is a step, not a reminder.

Tightening changes this wave: `<per item — the tightened contract, or "none">`.

- After the implementing agent returns, grep the whole repo (`src/main` AND `src/test`) for the
  affected type, tool or accessor and check every call site and fixture against the new contract.
- **Repairs are construction-only**: fix the call site or fixture to satisfy the contract (stub
  the new accessor, derive the dependent field, pass the now-required argument). Never relax the
  contract, weaken an assertion, or `@Disabled` a casualty to clear the wave.
- Fixture repairs are orchestrator commits, not author commits. Declare each one in the Review
  scoping commit map so a reviewer reading owned-file diffs can tell a repair from a test edit.
- A repair that cannot be made construction-only is an arbitration case (Step 4b), not a sweep
  outcome — escalate rather than absorbing it here.

## Docs

Implementers do not edit `current/docs/**`, `README.md` or `CLAUDE.md`. List the exact edits your
change requires under "Docs needed" in `implementation-notes`; a single serialized docs seat makes
them after the implementation wave, so two agents never write the same doc.

Docs seat for this wave: `<agent/seat, or "orchestrator">`.

## Notes

| Seat | Note keys it fills | maxLength |
|---|---|---|
| Implementer | `implementation-notes`, `session-tracking` | `<3000 / 2000>` |
| Test author | `test-manifest` | `<3000>` |
| Reviewer | `review-checklist`, `test-independence-audit` | `<1500 / …>` |
| Orchestrator | `delegation-metadata` | `<…>` |

Take the limits from the item's resolved schema, not from memory — an over-limit body is warned
or rejected per `note_limits.mode`. No seat calls `advance_item` or `manage_items`; the
orchestrator owns every transition.

## Review scoping

Reviews scope by OWNED FILES, not by SHA range:

```
git -C <wt> diff <base-sha>..HEAD -- <the reviewed item's owned files>
```

In a shared worktree the commits between a child's pre- and post-SHA interleave other streams'
work, and a later fix-up or formatter commit falls outside that range entirely — a SHA range both
over- and under-reports. The map below exists ONLY for the ownership two-range check
(`git -C <wt> show --stat <sha>` per commit: implementer commits touch no `src/test/**`, author
commits touch no `src/main/**`).

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
Red-proof results (orchestrator-run, fix reverted in a scratch worktree, item tests only):
`<per item — "N/M failed", or "compile-red (tests bind to NEW surface <name>)">`.
