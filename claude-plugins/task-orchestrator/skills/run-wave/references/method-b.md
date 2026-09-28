# Method B — direct Agent dispatch over the same run plan

Method B executes the **same** run plan (`run/<runId>`, the same per-item seat stages) as
Method A, but drives it with direct `Agent` calls from this session instead of handing it to the
`Workflow` tool. It exists for two reasons: `Workflow` may be unavailable (Step 0 F1), or the
server may not yet expose enough capability data for Method A to plan seat-aware dispatch (Step 0
F2/F3). Method B reuses Method A's core prompt-rendering and envelope-handling bytes
(`seatPrompt`, `handoff`, `mapEntry`, `mapStageResult`, `envelopeSchema`, `lockKeysFor`,
`normalizeArgs`, `preflight`) via `scripts/lib/wave-core.mjs`, so the two methods produce
identical seat prompts for identical inputs — parity by construction, not by convention.

All scheduling and bookkeeping below is driven by `node "<helper>" <cmd>` calls
(`scripts/run-exec-lib.mjs` under the hood); this reference describes the orchestration loop
around those calls, not the calls' internals.

---

## The loop

1. **Schedule.** `node "<helper>" next --plan <scratchpad>/run-wave/<runId>/plan.json --state <scratchpad>/run-wave/<runId>/state.json` returns `{dispatch: [...], waiting: [...], complete: bool}`. Dispatch **every** returned stage in `dispatch` in **one message** — a single batch of parallel `Agent` tool calls, not one call per turn. For each stage:
   - `prompt`: the exact text from `node "<helper>" prompt --plan <plan> --state <state> --item <short> --seat <seat>`
   - `model`: `stage.dispatch.model` (already resolved by the planner's dispatch precedence — never re-derive it here)
   - `subagent_type`: `stage.dispatch.agent ?? "general-purpose"`
   - **never** pass `isolation` — each item's worktree already comes from the run plan's `args.items[].worktree`; a Method B agent works inside that existing tree, it does not get its own fresh one
2. **Respect ordering.** `next` already honors `waitsFor` milestones and the same `lockKeysFor` overlap rule the script core uses (two stages that would write overlapping lock keys are never in the same dispatch batch; when they'd overlap, the higher-priority item's stage is returned and the other is held back for a later `next` call). Do not second-guess this — if a stage you expected is missing from `dispatch`, it's in `waiting` for a reason `next` already computed.
3. **Collect results.** For each dispatched Agent's return value, save the final text to a file and run `node "<helper>" stage-result --plan <plan> --state <state> --item <short> --seat <seat> --envelope <file>`. This extracts the last balanced JSON blob from the text, validates it against the envelope schema, and maps it through the same `mapEntry`/`mapStageResult` logic Method A's core uses.
   - **No parseable envelope found:** send exactly one `SendMessage` back to that agent asking it to re-emit only the envelope JSON, nothing else. If the second attempt still fails to parse, record that stage as `stopped: "invalid envelope"` and move on — do not retry a third time.
   - **Unknown `subagent_type`:** if the Agent dispatch itself fails because the requested `subagent_type` doesn't exist, retry once with `subagent_type: "general-purpose"` and record `agentTypeFallback: true` against that stage (parity with how Method A's core handles the same failure).
4. **Scan declarations.** Whenever a batch includes a `declarations-extractor` stage immediately followed by a seat that owns a `test-author`-skill note, run `node "<helper>" scan-declarations --in <the extractor's saved output file>` **before** that output becomes part of the test-author's prompt (`outs`). The scan flags behaviour-revealing words the extractor should not have leaked (mirrors the orchestrator-scan step the dispatch-contract template requires by hand for non-run-plan waves). Hits are stripped (`redacted: true` in the result) before the redacted text is folded into `outs`; the hit count and the flagged words go into the run's eventual `session-tracking` Friction section — this is a real signal about extractor drift, not noise to discard.
5. **Persist.** After each dispatch batch completes (all its `stage-result` calls done), `manage_plan_documents(operation="stash", rootId, slug="run/<runId>/state", body=<the updated state document>)`. Stash only the **state** document per batch — small, cheap to re-emit every round. The plan document itself (`run/<runId>`) is written once at Step 5 of the main skill and never rewritten mid-run.
6. **Repeat** from step 1 until `next` reports `complete: true`. At that point, hand off to `references/post-run.md`.

---

## Actor identity

Every Method B seat's `manage_notes`/`advance_item` calls use an actor of the form
`{id: "<seat>:<short>:<runId>", kind: "subagent", parent: "workflow:<runId>"}` — **not**
`parent: "orchestrator:main"`. The `workflow:` parent marker is what makes
`phase-guard-record.mjs` treat this as a run-plan seat (the same marker Method A's real
`Workflow` launch would produce), which keeps the SubagentStop guard from blocking the entry
seat on other seats' notes. This is a deliberate reuse of an existing marker, not a new
convention — do not invent a different `parent` value for Method B dispatches.

---

## Declared degradations vs Method A

Record these in `meta.degradations` (or the run's Friction notes) whenever Method B is chosen —
they are known, accepted gaps relative to Method A, not bugs to silently work around:

1. **No prompt-cache staggering.** Method A's `Workflow` runtime can stagger dispatch to make
   better use of prompt caching across agents; a hand-driven batch of `Agent` calls in this
   session cannot replicate that scheduling.
2. **One orchestrator turn per dispatch batch.** Each `next` → dispatch → collect cycle consumes
   one turn of this session, rather than the async notification pattern Method A uses. This
   inflates `orchestrator-turns` in the eventual provenance line relative to an equivalent
   Method A run — that's expected, not a defect.
3. **The declarations scan runs here but may not run under Method A yet.** Step 4 above is a
   capability Method B has today; whether Method A's script core runs the equivalent scan
   depends on whether the upstream core shipped `scanDeclarations` (see the design's OQ-1). Do
   not assume parity on this specific point even though the two methods otherwise share prompt
   bytes.

---

## Single-item exception

A **Delegated-tier single item** (not part of a multi-item wave) dispatched through `/implement`
stays on that skill's legacy hand-dispatch path rather than going through Method B — this run
plan machinery is for multi-item waves. If you find yourself about to run Method B over exactly
one item outside an explicit `/task-orchestrator:run-wave` invocation, stop and use the legacy
path instead.
