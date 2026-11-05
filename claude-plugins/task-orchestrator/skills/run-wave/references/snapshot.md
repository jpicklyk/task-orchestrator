# `run-wave/snapshot-v1` — field reference

The skill assembles this object once per run (Step 2) and writes it to
`<scratchpad>/run-wave/snapshot.json` — no `runId` exists yet at that point, so the path carries
no `<runId>/` segment. Once `plan` (Step 3) returns a `runId`, the skill copies this same
snapshot alongside the returned plan document to `<scratchpad>/run-wave/<runId>/snapshot.json`
and `<scratchpad>/run-wave/<runId>/plan.json`; every later step reads from that `<runId>/` pair.

This is a field list only — no guidance on HOW to fill each field. That prose lives in `SKILL.md`
Step 0 (capability probe) and Step 2 (the snapshot calls table).

```
{contract, rootId, ancestorId|null, capturedAt, features[], rulesServed:[{key, rulesVersion}],
 candidates:[{id, short, title, priority, complexity?, type, role, parentId, traits[], hasChildren, source:"ready"|"work"}],
 blocked:[{itemId, role, blockType, blockedBy:[{itemId, role, effectiveUnblockRole, satisfied}]}],
 schemas:{<itemId>: {status:"ok"|"not-found"|"unavailable", type, configFingerprint, configSource,
          notes:[{key, role, required, seat?, skill?}], seats?, dispatch?, dispatchBySeat?, resources?}},
 scopes?:{<itemId>: {files:[string], dependsOn?:[string]}},        // only candidates joined to a same-parent candidate by an unsatisfied dependency edge
 carryIns?:{<itemId>: {keys:[string], dispositions:string|null}},  // only candidates with >=1 carry-in-* note
 noteOwners:{<itemId>: [{key, seat}]},                 // only for role=work candidates (S4 resume)
 parents:{<id>: {role, canAdvance, missing[]}},         // S11
 profile:{…parsed run-profile.json, or {} when absent},
 probe:{…verbatim output of the `probe` subcommand},
 git:{repoRoot, originMain, worktrees:{<path>: {branch, head}}}}
```

## Fields not spelled out by name above

- `profile` — the parsed `.taskorchestrator/run-profile.json`, or `{}` when the file is absent.
  `plan` applies the project-profile defaults itself (`worktreeRoot`, `branchPrefix`, `maxItems`,
  `defaultModels`, `review`) and carries `appendOnlyFiles` (repo-relative glob patterns, canonicalised
  into `args.project.appendOnlyFiles`; see post-run.md) — the skill never fills those defaults in by hand before writing the
  snapshot.
- `probe` — the verbatim JSON the `probe` subcommand prints (`pluginVersion`, `phase0Hooks`,
  `hookMarkers`, `workflowSizeGuideline`, `allow`, `serverCompat`, and whatever else it emits — copy
  it as-is, do not curate the fields). Required. `chooseEntry` reads `probe.phase0Hooks` for entry
  mode and `plan` copies `probe.pluginVersion`/`probe.workflowSizeGuideline` into
  `capabilities`/`sizeGuideline`. `serverCompat` is checked in Step 0 F2 (non-null stops the run);
  `probe --no-server-check` skips that network check and `--api-url <url>` overrides the resolved URL.
- `profile.verify[].appliesTo` — optional, a non-empty array of repo-relative glob patterns (same
  segment-glob matching as `appendOnlyFiles`, case-insensitive). `promptVerify` renders the entry only when
  some planned file (planner `mainFiles`, `docFiles`, `testFiles`, `existingTestEdits[].file`) matches;
  otherwise `SKIP <name>: appliesTo matched no planned file`. With no planner output the entry renders.
- `profile.knownRed` — optional array of `{test, reason, expires?, tracking?}` (`expires` as `YYYY-MM-DD`, or
  `tracking`, required). `plan` copies live entries into `args.project.knownRed` and drops one whose
  `expires` is before the plan date with a warning `knownRed <test> expired <date>`.
- `profile.runBudget` — optional `{tokens?, costUsd?, attemptsPerSeat?, maxActiveSeats?}`: when set,
  `plan` copies it to `meta.runBudget` and `run-ops --phase open` sends it as the run's `budget`
  (absent means no cap; the server refuses a budget above its configured ceiling). `plan` also copies
  the snapshot's `ancestorId` to `meta.scopeId`, the run's `scopeId`.
- `noteOwners` — optional, `role:"work"` candidates only: `{key, seat}` per note whose
  `notes_read(select:"list")` entry shows `owner.seat` (shown only when the caller may see
  attribution). A resumed work seat whose every owned note is owned by that seat is skipped; a note
  with no owner row never skips a seat.
- `scopes` - optional. One entry per candidate that an unsatisfied dependency edge joins to another candidate with the same `parentId`. `files` is every repo path the item's planner queue note or summary names as a file to edit (including `Shared file, lock-serialized:` lines); `dependsOn` is the ids (short or full UUID) on its `Depends on <id>:` lines. Only the extracted paths and ids enter the snapshot, not the note body. In `shared` mode `plan` compares canonical `files` for exact equality (backslashes become `/`, empty and `.` segments dropped, drive letter lowercased, a `git.repoRoot` prefix stripped; entries with `*` or `?` are skipped) and puts one advisory `shared-file edge` string in `meta.warnings` per edge whose two scopes share a file and whose dependent has no matching `dependsOn`. A directory-vs-file overlap and a blocker that is not a candidate get no warning. `explain` prints the warnings on a `Warnings:` line.
- `carryIns` - optional. One entry per candidate that has at least one note key starting `carry-in-` (from `notes_read(select:"list", itemId)` with no `keys` filter); `keys` is those keys, `dispositions` is the body of the item's `carry-in-dispositions` note (`notes_read(select:"get", itemId, key:"carry-in-dispositions")`), or of the `## Carry-in dispositions` section of its planner queue note, else `null`. This is the one place a note body enters the snapshot. `plan` drops `carry-in-dispositions` and duplicates from `keys`, copies the result to each item's `carryIns`, and lists in `lateCarryIns` the keys of an item with no planner stage that no disposition line covers (a line covers a key only when it starts with the exact `<key>:`).
- `git.repoRoot` — `git rev-parse --show-toplevel`, forward-slashed, no trailing slash. Required:
  `plan` derives every item's `worktree`/`branch` path from it in `per-item` mode and (absent a
  `--worktree`/`--branch` override) in `shared` mode too.
- `candidates` — besides the ready and `role:"work"` items, include every non-terminal item in the target scope that `items_read(select:"blocked")` reports as blocked only by other candidates, with `source:"ready"`. `items_read(select:"next")` does not return them, and the planner reports any blocked item missing from `candidates` as `not projected`. Under `--entry pre-entered` an in-run edge becomes a cross-run deferral.
- `candidates[].traits` — the item's top-level `traits` array, as `items_read` `get`, `list` and
  `next` render it (the compact and full views both carry it). An absent `traits` member means `[]`,
  not an error.
- `blocked[]` — from `items_read(select:"blocked")`: `itemId` is the entry's `id`, `blockType` its
  `blockType`, and each `blockedBy` entry is one of its `blockers` (`id` → `itemId`, `role`,
  `threshold` → `effectiveUnblockRole`, `satisfied`). An explicit entry may also carry `reason`,
  `decisionId` and `resumable`, copied from the entry (all optional); the planner names them in the
  exclusion reason.
- Id forms - `candidates[].id` is identity and must be the full UUID (`items_read` `get`); it keys
  `schemas`, `scopes`, `carryIns` and `noteOwners`. Reference fields (`blocked[].itemId`,
  `blockedBy[].itemId`, `candidates[].parentId`) may be a full UUID or 8-32 hex characters, as the
  compact `blocked` and `next` views answer them (`blockers[].id` is a full UUID there). The planner
  resolves each reference to the unique candidate whose id starts with it before joining. No match
  keeps the reference as given: an unmatched `blocked[]` entry defers that item as `not projected`,
  while an unmatched `blockedBy[]` reference becomes a cross-run deferral. More than one match is
  a validation error naming `ambiguous`. `parentId` equality uses the same prefix rule.
- `parents` — from `items_read(select:"context", itemId:<parent>)`: `role` is `item.role`,
  `canAdvance` and `missing` are `gateStatus.canAdvance` and `gateStatus.missing`.
- `rulesServed` — the `config` `rules` list (`{key, rulesVersion}` per rule) for `rootId`.

## `validateSnapshot` errors this shape introduces

Structural checks beyond the ones already documented in the helper's own doc comment:

- `git.repoRoot must be a non-empty string` — fires when `git` is missing entirely, or
  `git.repoRoot` is missing, empty, or not a string.
- `profile must be an object` — fires only when `profile` is present and is not a plain object
  (an array, a string, `null`, …). An absent `profile` is valid and is treated as `{}`.
- `profile.appendOnlyFiles must be an array of strings` — the key is present but is not an array of
  non-empty strings (including `null`).
- `profile.appendOnlyFiles entry "<raw>" is not a repo-relative file pattern` — one per entry that is
  absolute, contains a `..` segment, is empty (`.`, `./`), or has only `*`/`**` segments.
- `profile.verify[<i>].appliesTo must be an array of repo-relative patterns` - the key is present but is
  empty, not an array of non-empty strings, or holds an absolute, drive-letter or `..` pattern.
- `profile.knownRed[<i>] must have test, reason and expires (YYYY-MM-DD) or tracking` - one per entry that
  lacks a non-empty `test` or `reason`, or has neither a valid `expires` date nor a `tracking` reference.
- `probe must be an object` — fires when `probe` is absent, `null`, an array, or any non-object.
  Unlike `profile`, `probe` is required: a plain object (even `{}`) passes.

## The `<scratchpad>` placeholder and `plan --scratchpad`

`profile.verify[].command` and `profile.searchScope[]` may carry the literal placeholder
`<scratchpad>` (see `run-profile.json`'s own shape) — only `plan --scratchpad <path>` substitutes
it. Pass `--scratchpad` on every `plan` call in this skill; it is not optional. If the profile
uses the placeholder and `--scratchpad` is omitted, `plan` refuses (exit 3):

```
plan: --scratchpad is required because the profile uses <scratchpad>
```

## The `<ownedTests>` placeholder (per-seat verify)

A `profile.verify[].command` may carry `<ownedTests>`. Unlike `<worktree>` and `<scratchpad>`, `plan`
never substitutes it: the planner has not run when `plan` builds `project.verify`, so the owned test
files are unknown. The implement-wave seat prompt (`promptVerify`, shared by Method B through
`wave-core.mjs`) substitutes it per seat, as a space-separated list of double-quoted paths (each path is
first relativized against the item worktree and then `args.repoRoot`, so a main-checkout absolute
path runs the worktree's copy; a path outside both roots stays absolute; relative
paths are prefixed with the item worktree; directory and glob entries pass through).

- Owned set: a `test-author` seat owns planner `testFiles` plus `existingTestEdits[].file`. An
  `implementer` on an item with no test-author stage owns the same union. An `implementer` on an item
  WITH a test-author stage owns `existingTestEdits[].file` only.
- No test owner: a seat-aware item (implementer stage not `implicitOwner`) with no test-author stage whose planner returns
  non-empty `testFiles` is stopped with `no-test-owner: add needs-test-author or clear testFiles (<list>)` before the
  implementer is dispatched (`existingTestEdits` alone never stops it). A seat-less item (`implicitOwner` implementer) is
  exempt. The planner prompt states `Test-author stage: present|absent` (no line on a seat-less item), and a planner may
  return `needsTestAuthor: true`; on an item with no test owner that stops it before any implementer pass with
  `no-test-owner: needs-test-author requested by planner` (checked right after `proceed: false`, before the
  envelope-mismatch stop). On that reason the orchestrator adds the `needs-test-author` trait (`items_write` update),
  writes the stopped planner's envelope (decisions, mainFiles, docFiles, existingTestEdits) into an orchestrator
  carry-in note `carry-in-frozen-plan` (under 1200 characters, carry-in shape of run-wave/SKILL.md Step 3) and
  relaunches; the rerun planner disposes it in scope and adds only the test-plan. Whenever an implementer has no test-author stage and the owned set is non-empty, its HANDOFF block ends with
  `TESTS ARE YOURS: <paths> - no test-author seat runs on this item; write, run and commit them yourself.` (testFiles then
  existingTestEdits files, exact duplicates once). Method A and B share one core, so both behave identically.
- `ownedTestsPattern` (optional regex source string, per entry) drops owned paths that do not match, so a
  `node --test` entry never receives `.kt` files.
- An empty owned set, or no planner output, renders `SKIP <name>: no owned test files` instead of a command.
  An invalid `ownedTestsPattern` renders `SKIP <name>: invalid ownedTestsPattern "<pattern>"` — the filter
  is never silently dropped.
- In shared-worktree runs, a non-empty VERIFY block also carries a contract line: failures whose paths
  are all owned by another item or seat are expected mid-wave; record them and continue.
- The whole-suite run belongs on `orchestrator`-seat entries (run after the wave, surfaced as
  `redProof.commands`). `<ownedTests>` is not supported on orchestrator entries.

`appliesTo` is evaluated before `<ownedTests>` handling. When `args.project.knownRed` is non-empty, implementer
and test-author VERIFY blocks also carry one `KNOWN RED (not a regression): <test> - <reason>` line per entry
(the `VERIFY:` header renders even for a seat with no verify entry); other seats get none. `run-exec verify`
echoes `knownRed` at the top level and `appliesTo` on `redProof.commands` entries; it filters nothing.

An older cached `implement-wave.js` prints an `<ownedTests>` command verbatim and ignores `appliesTo`. This
repo's profile carries `plugin-node-owned-tests` (`appliesTo` `claude-plugins/**`) beside the orchestrator's
`plugin-node-tests`, since the plugin cache carries `<ownedTests>` support at 4.0.0.
