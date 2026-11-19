# Deep mode and the audit sweep (user-run only; W5 relocates)

Not part of the automatic retrospective. Nothing in SKILL.md links here: the skill runs without `--deep`, and a hook-dispatched or background run never reaches these steps. They are kept for the user-run review skill that W5 adds, which relocates this file. Step numbers name the steps of SKILL.md.

## Recent retrospectives

The `--deep` input; the meta-evaluation (Step 8) reads its own page.

```to-call
{"tool": "items_read", "select": "list", "args": {"tags": ["session-retrospective"], "sort": "createdAt", "order": "desc", "limit": 10}}
```

## Deep mode

Only when `$ARGUMENTS` contains `--deep`, the user invoked this skill in the main session (never a hook-dispatched or background run), and the Workflow tool is callable; otherwise ignore the flag and match inline. The workflow replaces the inline matching, is read-only, and Step 6 still does every write.

1. Build `retro-analysis/args-v1`: `contract`, `runId: "ra-<YYYYMMDD>-<HHMM>"`, `date`, `mode: "deep"`, `rootId` (when a project root is known), `findings` (each Step 3 finding as `{fid: "f<n>", dimension, text, keywords}`; `dimension` maps 3a `schema-effectiveness`, 3b `delegation`, 3c `note-quality`, 3d `plan-to-execution`, 3e `friction`), `trends` (the 4.2 listing as `{id, short, title}`), `observations` (one unscoped `items_read` `list` with `tags: ["agent-observation"]`, `limit: 100`, terminal ones dropped), `retros` (the second 4.2 block), and `models` and `efforts` keyed `match`, `cluster` and `adjudicate`, each the `research` class's model and effort from `task-orchestrator:model-effort`. Only ids and titles go in; the agents read summaries and notes themselves.
2. Unless dry-run, and only with a known `rootId`, stash `{findings, retroScope}` (retroScope = the Step 1 root and item ids) as a plan document and add `planDocSlug: "retro/<runId>"` to the args:

```to-call
{"tool": "docs_write", "op": "upsert", "args": {"rootId": "<rootId>", "kind": "plan", "slug": "retro/<runId>", "body": "<JSON of findings and retroScope>"}}
```

3. Launch `Workflow({name: "task-orchestrator:retro-analysis", args})` with args as a real object, then end the turn.
4. On the task notification: if `started: false`, report its `reason` and match inline. Otherwise the result echoes `findings` (after compaction re-read the stash with `docs_read` `get {rootId, kind: "plan", slug: "retro/<runId>"}`); resume at Step 5:
   - `matched` entries are 4.3 recurrences: Step 6 increments `Sessions: N` from `sessionsBefore`, or from the listing summary when it is null;
   - `stats.unmatchedShards` (`[{label, kind, targetIds}]`) are shards whose Match agent returned nothing: match the findings against those `targetIds` inline per 4.3 before calling any finding new, and report `stats.missing`;
   - `newTrends` and `unresolved` fids are new-pattern candidates; `staleTrends` are retire candidates; `observationLinks` feed the report.
5. Skip 4.4 for matched trends whose `evidence` the result already carries.

## Audit sweep

The `retro-analysis` workflow's audit mode (a standalone sweep, no findings) returns the same `newTrends`, `staleTrends` and `observationLinks` shapes. Present that result to the user first; only after they confirm, apply this step's create and retire shapes to it.
