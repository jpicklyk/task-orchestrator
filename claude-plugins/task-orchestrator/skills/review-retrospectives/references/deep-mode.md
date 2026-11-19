# Deep mode and the audit sweep

User-run only, from Step 2 of the review-retrospectives skill, in the main session with the Workflow tool callable. Nothing here writes: the matching result feeds Step 3, which writes only what the user confirms.

## Recent retrospectives

The `--deep` input, the same page Step 1 reads (at most 30 retrospectives; the findings cap is 40):

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["session-retrospective"], "sort": "createdAt", "order": "desc", "limit": 10}}
```

## Deep mode

Only with `--deep`, and only when at least one retrospective has a `workflow-evaluation` note; otherwise ignore the flag and match inline. The workflow replaces inline matching and is read-only.

1. Findings: from each recent retrospective's `workflow-evaluation` note take every finding as `{fid: "f<n>", dimension, text, keywords}`. `dimension` maps 3a `schema-effectiveness`, 3b `delegation`, 3c `note-quality`, 3d `plan-to-execution`, 3e `friction`.
2. Build `retro-analysis/args-v1`: `contract`, `runId: "ra-<YYYYMMDD>-<HHMM>"`, `date`, `mode: "deep"`, `rootId` (when known), `findings`, `trends` (Step 1's active trends as `{id, short, title}`), `observations` (one unscoped `items_read` `list`, `tags: ["agent-observation"]`, `limit: 100`, terminal ones dropped), `retros` (Step 1's retrospectives as `{id, short, title}`), and `models` and `efforts` keyed `match`, `cluster` and `adjudicate`, each the `research` class's model and effort from `task-orchestrator:model-effort`. Only ids and titles go in.
3. With a known `rootId`, stash `{findings, retroScope}` as a plan document and add `planDocSlug: "retro/<runId>"` to the args:

```to-call
{"tool": "docs_write", "op": "upsert", "args": {"rootId": "<rootId>", "kind": "plan", "slug": "retro/<runId>", "body": "<JSON of findings and retroScope>"}}
```

4. Launch `Workflow({name: "task-orchestrator:retro-analysis", args})` with args as a real object, then end the turn.
5. On the task notification: if `started: false`, report its `reason` and match inline. Otherwise the result echoes `findings` (after compaction re-read the stash with `docs_read` `get {rootId, kind: "plan", slug: "retro/<runId>"}`) and Step 2 resumes:
   - `matched` entries are recurrence candidates for Step 3, which increments `Sessions: N` from `sessionsBefore`, or from the trend summary when it is null;
   - `stats.unmatchedShards` (`[{label, kind, targetIds}]`) are shards whose Match agent returned nothing: match the findings against those `targetIds` inline before calling any finding new, and report `stats.missing`;
   - `newTrends` and `unresolved` fids are new-trend candidates; `staleTrends` are retire candidates; `observationLinks` feed the dashboard.

## Audit sweep

With `--audit` the `retro-analysis` workflow runs a standalone sweep (`mode: "audit"`, no findings) and returns the same `newTrends`, `staleTrends` and `observationLinks` shapes. Present that result to the user first; only after they confirm, Step 3 applies its create and retire shapes to it.
