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
 noteActors:{<itemId>: [{key, actorId}]},              // only for role=work candidates (S4 resume)
 parents:{<id>: {role, canAdvance, missing[]}},         // S11
 profile:{…parsed run-profile.json, or {} when absent},
 probe:{…verbatim output of the `probe` subcommand},
 git:{repoRoot, originMain, worktrees:{<path>: {branch, head}}}}
```

## Fields not spelled out by name above

- `profile` — the parsed `.taskorchestrator/run-profile.json`, or `{}` when the file is absent.
  `plan` applies the project-profile defaults itself (`worktreeRoot`, `branchPrefix`, `maxItems`,
  `defaultModels`, `review`) — the skill never fills those defaults in by hand before writing the
  snapshot.
- `probe` — the verbatim JSON the `probe` subcommand prints (`pluginVersion`, `phase0Hooks`,
  `workflowSizeGuideline`, `allow`, and whatever else it emits — copy it as-is, do not curate the
  fields). Required. `chooseEntry` reads `probe.phase0Hooks` for entry mode and `plan` copies
  `probe.pluginVersion`/`probe.workflowSizeGuideline` into `capabilities`/`sizeGuideline`.
- `git.repoRoot` — `git rev-parse --show-toplevel`, forward-slashed, no trailing slash. Required:
  `plan` derives every item's `worktree`/`branch` path from it in `per-item` mode and (absent a
  `--worktree`/`--branch` override) in `shared` mode too.
- `candidates` — besides the ready and `role:"work"` items, include every non-terminal item in the target scope that `get_blocked_items` reports as blocked only by other candidates, with `source:"ready"`. `get_next_item` no longer returns them, and the planner reports any blocked item missing from `candidates` as `not projected`. Under `--entry pre-entered` an in-run edge becomes a cross-run deferral.
- `candidates[].traits` — the item's `properties` field is a JSON string; parse it and pull out
  the `traits` array. An absent `properties` or an absent `traits` key inside it both mean `[]`,
  not an error.

## `validateSnapshot` errors this shape introduces

Two structural checks beyond the ones already documented in the helper's own doc comment:

- `git.repoRoot must be a non-empty string` — fires when `git` is missing entirely, or
  `git.repoRoot` is missing, empty, or not a string.
- `profile must be an object` — fires only when `profile` is present and is not a plain object
  (an array, a string, `null`, …). An absent `profile` is valid and is treated as `{}`.
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
`wave-core.mjs`) substitutes it per seat, as a space-separated list of double-quoted paths (relative
paths are prefixed with the item worktree; directory and glob entries pass through).

- Owned set: a `test-author` seat owns planner `testFiles` plus `existingTestEdits[].file`. An
  `implementer` on an item with no test-author stage owns the same union. An `implementer` on an item
  WITH a test-author stage owns `existingTestEdits[].file` only.
- `ownedTestsPattern` (optional regex source string, per entry) drops owned paths that do not match, so a
  `node --test` entry never receives `.kt` files.
- An empty owned set, or no planner output, renders `SKIP <name>: no owned test files` instead of a command.
- In shared-worktree runs, a non-empty VERIFY block also carries a contract line: failures whose paths
  are all owned by another item or seat are expected mid-wave; record them and continue.
- The whole-suite run belongs on `orchestrator`-seat entries (run after the wave, surfaced as
  `redProof.commands`). `<ownedTests>` is not supported on orchestrator entries.

This repo's `run-profile.json` ships `plugin-node-owned-tests` (`node --test <ownedTests>`, pattern
`\.test\.mjs$`, seats `implementer` and `test-author`) beside the orchestrator's `plugin-node-tests`.
