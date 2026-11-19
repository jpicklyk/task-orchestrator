# Accept - project-scoped

The project-scoped Accept path of review-proposals Step 5. The `proposal` note is already ensured ("Before any Accept"). Shared GitHub conventions are not used here. The Source-trend write-back at the end of Step 5 follows this path.

1. **Extract the exact YAML** from the proposal note. If it has none ready to apply, draft the edit, show it as a diff against the current config file, and confirm via `AskUserQuestion` before applying anything.
2. **A different `rootId`.** If the proposal targets a different `rootId` than this workspace's `project.rootId`, do not edit this config. Upsert `adoption-decision` recording that the change must be applied from the owning workspace, and treat the item as **deferred** (leave it in queue; do not advance).
3. Otherwise apply the change (additive and targeted, not a rewrite). A config change is an **Edit** of the config file. A rule change is an edit of `.taskorchestrator/rules/<key>.md`, which config-sync pushes at the next session start (the file-change hook watches only `config.yaml`), so confirm it is served (`config` `rules`) before closing: steps 4 and 5 (validate and push `config.yaml`) apply only to config changes, so for a rule change skip to step 6. An accepted process change (which passes run, what a note must cover, which skill a note points to) lands in config or a rule, not in skill or plugin text; skill text changes only for mechanics (Task Orchestrator mechanics in the plugin, a project's own mechanics in its project skills) or generic engineering practice (the plugin's engineering skills). If the proposal names skill text for a process change, retarget it to the owning config layer and show that retargeted edit before applying it.
4. **Validate** (config changes only) the full file text, LF-normalized, before pushing. Act on `data.results[0].valid` and `data.results[0].errors` (each `{path, reason}`); validate reports an invalid document there, it does not fail the element with `config_invalid`. Strict validation rejects an unknown key: a top-level `retrospective:` or `orchestration:` block fails, so move it under `extensions.claude-code`. Rules: `current/docs/config-reference.md`.

```to-call
{"tool": "config", "op": "validate", "args": {"rootId": "<rootId>", "configYaml": "<full file text>"}}
```

5. **Read the head version** (config changes only), then **push** with it as `expectedVersion`:

```to-call
{"tool": "config", "op": "get", "args": {"rootId": "<rootId>"}}
```

```to-call
{"tool": "config", "op": "push", "args": {"rootId": "<rootId>", "configYaml": "<full file text>", "expectedVersion": "<head version from get>"}}
```

   `expectedVersion` is the `data.results[0].version` that `get` returned (an integer, 1 or greater; `0` is `invalid_request`). When the root has no config yet, `get`'s element fails with `not_found`: omit `expectedVersion` from the push. Then check `ok` and `data.results[0].ok`; on a failure read `data.results[0].error.code`. On `version_conflict` (`error.detail` `{expected, actual}`) never force blindly: `get` the head again. If its version differs from the one you sent, the head moved: diff its `configYaml` against the local file, show the user, and once they confirm the local file wins re-push with the new head version as `expectedVersion`. If it still equals what you sent, the local text matches an older version (superseded): re-push with `force: true` and the same `expectedVersion` only after the user confirms that revert. `force` never skips the compare-and-set. `config_invalid` carries `error.detail.errors` as `{path, reason}` pointers: fix and re-validate.
6. Upsert the closure note:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "<uuid>", "key": "adoption-decision", "role": "work", "body": "accepted - <rationale>. Applied to <config or rule file actually edited> (<section>), pushed per-root <rootId> on <YYYY-MM-DD> (a rule change: record the date `config` `rules` first served it, not the edit date)."}}
```

7. Advance with `start` twice, as two sequential calls (queue to work, then work to review):

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "<uuid>", "trigger": "start"}}
```

   Read `ok` and `data.results[0].newRole`; on a failure `data.results[0].error.code` (`gate_blocked` lists `error.detail.missing`). Do **not** use `complete`: it jumps to terminal and gate-checks required notes across all phases, so the intentionally unfilled `outcome-verification` note would block it. `start` checks only the current phase's notes and lands in `review`.
8. **Stop in `review`.** Tell the user the item dwells in `review` with `outcome-verification` intentionally unfilled: `/session-retrospective` Step 8a verifies whether the change helped, fills it and completes the item once at least 2 later retrospectives exist. This is by design; the item shows under Awaiting verification until then.
9. Apply the **Source-trend write-back** below: the config change is applied, so the source trend retires now.

## Troubleshooting

**`version_conflict` on a config push.** The head moved since this workspace last read it (another session, `manage-schemas` or `quick-start` pushed). `get` the head, diff, show the user exactly what differs, and after explicit confirmation re-push with the new head version as `expectedVersion`. `force: true` bypasses only the superseded-content guard (local text equal to an older version), never the compare-and-set.

**`config_invalid` on push.** The element error carries `error.detail.errors`; `validate` reports the same problems as `valid: false` with `errors`. The usual cause is a section written at the top level that belongs under `extensions.claude-code` (`retrospective`, `orchestration`, `actor_attribution`).

**Proposal has no exact YAML.** The body describes the change in prose only. Draft the edit, show it as a diff, and get explicit confirmation via `AskUserQuestion` before applying. Do not guess silently and push.

**Proposal targets a different workspace's `rootId`.** It was likely created in another project sharing the database. Do not edit this config. Record in `adoption-decision` that the change must be applied from the owning workspace (name the `rootId` if known), and leave the proposal in `queue`.
