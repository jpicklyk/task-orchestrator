# Method B — direct Agent dispatch over the same run plan

Method B executes the **same** run plan (`run/<runId>`, the same per-item seat stages) as
Method A, but drives it with direct `Agent` calls from this session instead of handing it to the
`Workflow` tool. It exists for two reasons: `Workflow` may be unavailable (Step 0 F1), or the
server may not yet expose enough capability data for Method A to plan seat-aware dispatch (Step 0
F2/F3). Method B reuses Method A's core prompt-rendering and envelope-handling bytes
(`seatPrompt`, `handoff`, `mapEntry`, `mapStageResult`, `envelopeSchema`, `lockKeysFor`,
`normalizeArgs`, `preflight`) via `scripts/lib/wave-core.mjs`, so a Method B seat prompt is
the Method A bytes plus a Method-B-only RETURN addendum (the envelope schema block step 1 documents):
parity holds for the core-rendered part by construction, and the addendum is the only difference.

All scheduling and bookkeeping below is driven by `node "<helper>" <cmd>` calls
(`scripts/run-exec-lib.mjs` under the hood); this reference describes the orchestration loop
around those calls, not the calls' internals.

---

## The loop

1. **Schedule.** `node "<helper>" next --plan <scratchpad>/run-wave/<runId>/plan.json --state <scratchpad>/run-wave/<runId>/state.json` returns `{dispatch:[{item, seat, model, agentType, lockKeys}], waiting:[{item, seat, on}], settled:[{item, status, reason}], complete}`. `waiting` entries name what they're blocked on (`on`, e.g. `'planner <short>'` or a lock key); `settled` entries are items `next` has already resolved for this call (deferred, refused, or complete) — there is nothing left to dispatch for them. Dispatch **every** entry in `dispatch` in **one message** — a single batch of parallel `Agent` tool calls, not one call per turn. For each entry:
   - `prompt`: the exact text from `node "<helper>" prompt --plan <plan> --state <state> --item <short> --seat <seat>` — for a Method B plan (`meta.method === "B"`) it ends with a METHOD B RETURN addendum stating that no StructuredOutput tool exists, that the envelope is the last JSON object of the final message, and the exact envelope schema inlined (planner prompts also carry a line superseding the core StructuredOutput sentence); pass it verbatim
   - `model`: the entry's own `model` field (already resolved by the planner's dispatch precedence — never re-derive it here)
   - `subagent_type`: the entry's own `agentType ?? "general-purpose"`. A `test-author` seat whose dispatch names no agent defaults to `task-orchestrator:test-author` (the blind test author) under Method B, so `agentType` is already set for it
   - **never** pass `isolation` — each item's worktree already comes from the run plan's `args.items[].worktree`; a Method B agent works inside that existing tree, it does not get its own fresh one
2. **Respect ordering.** `next` already honors `waitsFor` milestones and the same `lockKeysFor` overlap rule the script core uses (two stages that would write overlapping lock keys — equal files, or a directory/glob and a path under it — are never in the same dispatch batch; when they'd overlap, the higher-priority item's stage is returned and the other is held back for a later `next` call). Do not second-guess this — if a stage you expected is missing from `dispatch`, it's in `waiting` for a reason `next` already computed.
3. **Collect results.** For each dispatched Agent's return value, save the final text to a file and run `node "<helper>" stage-result --plan <plan> --state <state> --item <short> --seat <seat> --envelope <file> [--agent-type <t>] [--agent-type-fallback]`. This extracts the last balanced JSON blob from the text, validates it against the envelope schema, and maps it through the same `mapEntry`/`mapStageResult` logic Method A's core uses, printing `{status, reason, state}`. **The orchestrator MUST overwrite `<scratchpad>/run-wave/<runId>/state.json` with the printed `state` before running the next `stage-result` or `next` call** — the command returns the new state as JSON, it does not write the file itself. Because each call reads the state the *previous* call wrote, **run `stage-result` calls one at a time, never in parallel** — two calls issued together would both read the same pre-update state, and the second write would silently discard the first.
   - **No parseable envelope found:** send exactly one `SendMessage` back to that agent asking it to re-emit only the envelope JSON, nothing else. If the second attempt still fails to parse, record that stage as `stopped: "invalid envelope"` and move on — do not retry a third time.
   - **Unknown `subagent_type`:** if the Agent dispatch itself fails because the requested `subagent_type` doesn't exist, retry once with `subagent_type: "general-purpose"`, then pass `--agent-type <the originally requested type>` and `--agent-type-fallback` to that stage's `stage-result` call so the recorded state carries `agentTypeFallback: true` (parity with how Method A's core handles the same failure).
4. **Scan declarations.** Whenever a batch includes a `declarations-extractor` stage immediately followed by a seat that owns a `test-author`-skill note, run `node "<helper>" scan-declarations --in <the extractor's saved output file>` after collecting that stage's result. This is a **report only** — `seatPrompt` (in the shared `wave-core.mjs` core, the same one Method A's `Workflow` run uses) already runs the same `scanDeclarations` pass when it renders the test-author's prompt, so `outs` keeps the extractor's **unredacted** output; Method B must not diverge from Method A by stripping words here that Method A's core did not strip. The scan's purpose in this loop is purely to surface drift as a signal: the hit count, the flagged words, and the `redacted` field (the redacted TEXT, never written back into `outs`) go into the run's eventual `session-tracking` Friction section — evidence the extractor leaked behaviour language, not something to silently launder before the test author reads it.
5. **Persist.** After each dispatch batch completes (all its `stage-result` calls done), stash the updated state document: `manage_plan_documents(operation="stash", rootId, slug="run/<runId>/state", bodyFromFile=<scratchpad>/run-wave/<runId>/state.json)` when the server can resolve that path (relative to `AGENT_CONFIG_DIR`), else `body=<the same state document, inline>` as a fallback — note the fallback in the run's Friction section either way. Stash only the **state** document per batch — small, cheap to re-emit every round. The plan document itself (`run/<runId>`) is written once at Step 5 of the main skill and never rewritten mid-run.
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
3. **No gap on the declarations scan — parity confirmed, not a degradation.** Method A's core
   (`seatPrompt` in the shared `wave-core.mjs`) already runs `scanDeclarations` before it renders
   the test-author's prompt; Step 4 above's `scan-declarations` call is Method B reporting on the
   same pass, not doing extra work Method A skips. Listed here only so a reader scanning this
   section for gaps sees this one was checked and closed, not left open.

---

## Single-item exception

A **Delegated-tier single item** (not part of a multi-item wave) dispatched through `/implement`
stays on that skill's legacy hand-dispatch path rather than going through Method B — this run
plan machinery is for multi-item waves. If you find yourself about to run Method B over exactly
one item outside an explicit `/task-orchestrator:run-wave` invocation, stop and use the legacy
path instead.
