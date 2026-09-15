# Dispatch contract template

Copy everything below the horizontal rule into `plans/<slug>.md` at Step 2 of `/implement` for
every Parallel-tier run (3+ children), fill every `<placeholder>`, and reference the result by
path from every Step 4, 4b and 5 dispatch prompt.

- **The slots that carry rules are copied verbatim** — Conflict rule, Commit discipline, Compile
  self-check, Review scoping. Only the `<placeholders>` inside them change. Do not paraphrase
  them into a dispatch prompt; point the agent at the slot instead.
- **Delete no slot.** A slot that does not apply is filled with `n/a — <why>`, so a reader can
  tell a decision from an omission.
- **Slot order is fixed** so an agent told "see the Compile self-check slot" finds it without
  reading the whole file: Header · Conflict rule · Items · Planning seat return template ·
  Commit discipline · Compile self-check · File ownership · Test author protocol · Docs ·
  Notes · Review scoping.

The Review scoping slot's commit map and the per-item red-proof results are filled as the wave
runs, not at Step 2. Every other slot is complete before the first dispatch.

---

# <Wave or feature title> — dispatch contract

Feature branch: `feat/<slug>`
Feature worktree: `<absolute path, e.g. D:\Projects\task-orchestrator\.claude\worktrees\feat-<slug>>`
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
value for any field except `main-files`/`test-files`). Field semantics: `/implement` SKILL.md
Step 3, "Planning seat" subsection — prose describes, this states.

```
<short-uuid>:
diagnosis-corrections: <file:line corrections to diagnosis/task-scope, or none>
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
- Commit by pathspec:
  `git -C <wt> commit --only -m "<type>(<scope>): <title> [<short-uuid>]" -m "<why>" -- <owned paths>`
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
powershell -File "<scratchpad>\gradle-locked.ps1" -Worktree "<feature worktree>" -Tasks ":current:ktlintFormat",":current:compileKotlin",":current:compileTestKotlin"
```

- Pass `-Tasks` as a comma-separated ARRAY LITERAL of quoted strings, exactly as written above.
  A nested `powershell -File ... -Tasks a,b,c` flattens the `[string[]]` parameter into a single
  string and the run is lost.
- `EXIT=0` → commit.
- `EXIT≠0` and EVERY error names a file outside your owned list → record the foreign file(s) and
  the error text under **Friction** in `session-tracking`, commit anyway, and return. Do **not**
  retry: the file belongs to another agent mid-flight and will change under you.
- `EXIT≠0` with at least one error in a file you own → fix and re-run, **max 3 invocations
  total**. Still red on your own files: commit nothing, report the error verbatim, return.
- Never run `:current:test` or `:current:ktlintCheck` — full test and lint verification is
  orchestrator-owned.
- `:current:ktlintFormat` may reformat files you do not own. Never stage those.

## File ownership

One row per stream. The listed files are that agent's ENTIRE writable scope; anything absent
belongs to another agent or to the orchestrator.

| Stream | Item | Impl model | Production files owned (src/main unless noted) | New test files (author-owned) |
|---|---|---|---|---|
| `<stream>` | `<short-uuid>` | `<sonnet\|opus>` | `<path (the specific change, so a reader can tell scope from a filename)>` | `<path>` |

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

**Declarations — the orchestrator fills one block per item before dispatch. The author looks up
nothing.**

```
DECLARATIONS for <short-uuid> — verbatim and complete
<every public declaration the tests touch: types and data-class constructors with full parameter
lists and defaults; function and method signatures; constants; enum values; and any KDoc carrying
an oracle or stating an invariant — including the validate() the fixtures must satisfy>
```

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
   diff. Asking costs a round-trip; the lookup costs the dispatch.
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
8. **Scope:** create ONLY the new test files the File ownership slot lists for your item. Never
   `src/main`, never docs, never another item's files, never a shared test harness.
9. **Forbidden:** `assumeTrue` on non-platform conditions, `@Disabled` / `@Ignore`, disjunctive
   escapes (`|| isEmpty()`), assert-not-null-only, swallowed exceptions, sleep-until-green.
10. **Compile self-check** ONCE, per the Compile self-check slot. Foreign-file errors → record,
    commit, do not retry.
11. **Commit** per the Commit discipline slot:
    `git -C <wt> add <your paths>` then
    `git -C <wt> commit --only -m "test(<scope>): <title> [<short-uuid>]" -m "<why>" -- <your paths>`,
    then `git -C <wt> show --stat HEAD` to verify.
12. **`test-manifest`** (`manage_notes` upsert, role `work`, max `<3000>`): actor id
    `test-author:<short-uuid>`; test file paths; commit SHA; S-id → test mapping (`covered:
    <method>` / `not-covered: <reason>`); probes run, including the ones that found nothing;
    forbidden-pattern declaration; invariants respected; red-proof shape obtained per
    `NEW-SURFACE` scenario; arbitration record.
13. **Call no `advance_item` and no `manage_items`.** Return ONE line:
    `<short-uuid>: commit <sha> | files: <list> | scenarios: <covered>/<total> | compile: EXIT=<n> | missing-declaration: <none or name>`

On a breach of any rule above, deliberate or accidental: stop, commit nothing, delete any draft
written after it, and disclose exactly what was read — in the return line and in the manifest's
arbitration record. A disclosed breach costs a re-dispatch; an undisclosed one costs the item's
independence verdict.

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
| `<short-uuid>` | `<shas>` | `<shas>` | `<arbitrations, declared contract changes, independence caveats — or "—">` |

Build state reviewers rely on: `<:current:test N tests / 0 failed and :current:ktlintCheck green
at <sha>>`. Reviewers do NOT run gradle.
Red-proof results (orchestrator-run, fix reverted in a scratch worktree, item tests only):
`<per item — "N/M failed", or "compile-red (tests bind to NEW surface <name>)">`.
