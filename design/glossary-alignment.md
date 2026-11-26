# Glossary alignment backlog

Each row below is a place where code, skills, hooks or docs disagree with [GLOSSARY.md](../GLOSSARY.md) as revised on 2026-11-23. The source is the user-journey work ([user-journey-map.html](user-journey-map.html)) and the vocabulary discussion that followed it. Every row is a candidate item, and none has been changed yet.

## The model these rows align to

- **Seats do the work, the orchestrator drives it, and the user decides, or the director decides for them.**
- **Two session layers:**
  - The **orchestrator** drives: it dispatches, advances, resumes and carries decisions up. It never answers. Its scope is ad hoc items or one run.
  - The **director** decides within class authority. It researches, answers and escalates, and never drives.
- **Two session modes:**
  - **Orchestration mode** is the default: the user's session is the orchestrator and the user decides.
  - **Director mode**: the user's session is the director.
  - An open run is an indicator, not a mode.
- **"Coordinator" is retired as a concept.** It was an orchestrator scoped to one run. The word stays in code symbols until a major version.
- **Decisions get a role-neutral surface, `decisions_read` and `decisions_write`.** Authority comes from the decision class and the credential, not from the profile.

## A. Decisions surface (server, tool contract)

| # | Where | What disagrees | Fix shape | Kind |
|---|-------|----------------|-----------|------|
| A1 | `runs_write` `raise`, `answer`, `ack`, `withdraw`; `runs_read` `decisions`, `decision` ([api-reference.md:66-67](../current/docs/api-reference.md)) | Decisions live under the run tools and are split by profile: raise is orchestration-only, answer is director-only, and reading is director-only. Decisions are not run-owned, since raise works with only an `itemId` or `rootId`. | Add `decisions_write` (`raise`, `answer`, `ack`, `withdraw`) and `decisions_read` (`list`) on the orchestration and director profiles. Keep the `runs_*` decision ops as deprecated aliases in 4.x and remove them at 5.0. Follow `.claude/skills/add-component/SKILL.md` and regenerate the goldens, the API reference and the call shapes. | server |
| A2 | Proposed `decisions_read` | (new) | It has one select, `list`, returning full records, with filters `itemId`, `ancestorId`, `runId`, `rootId`, `decisionIds` (which replaces `get`), `status` (default `open`), `class` and `severity`. It returns a top-level `classes` map with one policy per class present, and `context` only with `includeContext: true`. Paging is 50 with `page.next`. The tool description notes why its list returns bodies when `notes_read` `list` does not: decision records are small and bounded. | server |
| A3 | Who may answer | The profile acts as the authority, which contradicts the CLAUDE.md rule that "the director path is not a security boundary". | Answer authority comes from the class's `answerers` and the caller's credential capability and scope. `decision-guard` keeps denying subagent answers and must match `decisions_write`. | server + hook |
| A4 | Answer provenance ([api-reference.md:2215](../current/docs/api-reference.md)) | Provenance carries only kind and profile. On stdio, a user's answer relayed by the orchestrator and a director's autonomous answer look the same. | Record who decided: the user, with their words verbatim in the rationale, or the director acting autonomously. | server |
| A5 | `items_read` `context` for an item | It shows a blocked item's bare `decisionId`, so the common "why is this blocked and what is asked" check costs a second call. | Add the item's open decisions (id, class, question, recommended option) to `context`. | server |
| A6 | Decision class config (`answerers`) | No field says whether the director may answer a class or must ask the user, and the decision read carries no class policy. | Add a per-class autonomy field (for example `director: answer \| ask`), served in the `classes` map of A2. | server + schema |
| A7 | `AdvanceService.kt:940-945` | `resume` is not refused while the item's decision is still open, which is a convention only. | Refuse it in the resume path. | server |

## A+. The board (decided 2026-11-23: the board is the user's view in both modes)

| # | Where | What disagrees | Fix shape | Kind |
|---|-------|----------------|-----------|------|
| A8 | `runs_read` `decisions`, `report` `rollup` (director profile only) | The board is the user's view in either mode, but half its reads are not served on the orchestration profile. | Serve them on the orchestration profile, alongside A1 (`decisions_read`). `runs_read` `frontier` is already served on both. | server |
| A9 | `skills/work-summary`, `skills/director` | There are two renderings of the board, and nothing on the board hands work to the director. | One board rendering. In director mode, add a way to hand work to the director from it ("handle these", "decide this class from now on"). | plugin |
| A10 | `skills/director/SKILL.md`, director hook text | The text says the director owns or is the board. | Reword: the director acts on the board at the user's instruction. Fold this into t3, which qualifies "director". | plugin |

## A++. Director mode on the orchestrator's tools (option C, decided 2026-11-23)

| # | Where | What changes | Kind |
|---|-------|--------------|------|
| A11 | `agents/director.md`, `skills/director/SKILL.md`, `init` registration | The hardened `claude --agent director` entry stops being the default. Director mode runs in an ordinary session on the orchestration connection. | plugin |
| A12 | `hooks/director-guard.mjs` | This becomes the one place altitude is enforced: deny body reads (note and document bodies, diffs, test output) and edits; allow skill and plugin files, a plugin's scripts (decision record 0014) and background dispatch. Its coverage list must track every tool select that returns a body. | hook |
| A13 | GLOSSARY.md (Director mode, Director profile); root CLAUDE.md Altitude rule | Once implemented, both describe altitude as enforced by the guard. The director profile stays served (2026-11-15 ruling) but is optional. | prose |

## B. Session layers (prose, skills, hooks)

| # | Where | What disagrees | Fix shape | Kind |
|---|-------|----------------|-----------|------|
| B1 | Root `CLAUDE.md` "Session Layers" | There are three layers (orchestrator, coordinator, director), with a separate Coordinator row, and rules phrased as "a coordinator never answers". | Rewrite as two layers plus seats. Use "an orchestrator never answers; in orchestration mode it records the user's own words", "the orchestrator resumes an item, inside or outside a run", and "a run's orchestrator may be a disposable background agent that resumes from the run view and its run files". | prose |
| B2 | "coordinator" in prose: about 660 mentions in 70 plugin files, 104 in `current/docs`, 171 in the `orchestration-mode` mod | It names a concept the glossary has retired. | Sweep prose, skills, hook strings and docs to "the run's orchestrator" or "orchestrator". Leave code symbols, wire fields and the DB column alone (see C1). Skill text is pinned by the Node tests, so run the full node suite. | plugin + docs |
| B3 | `orchestration-mode` mod footer label `coordinator` | It presents an open run as a separate mode. | Show `orchestrator` with a run indicator. | mod |
| B4 | `claude-plugins/task-orchestrator/skills/director/SKILL.md:192-193` | The director answers only when "they told you how to answer this class", a conversation instruction, and it never dispatches research. | An autonomous answer loop: read the inbox, dispatch a research agent when evidence is needed, answer what the class (A6) allows, and escalate the rest using the glossary's Escalation criteria. | plugin |
| B5 | `run-wave/SKILL.md:94`, `work-summary/SKILL.md:114` | An orchestrator in the user's session "shows the user the report" and waits, and work-summary sends the user to director mode. Nothing collects the user's answer in orchestration mode. | Add an inline decision card (`AskUserQuestion`) that answers through `decisions_write` with the user's words, plus a hint when `decision.raised` arrives. The server already streams that event over SSE, but no hook consumes it. | plugin + hook |
| B6 | Run-less decisions | A decision raised with only an `itemId` or `rootId` has no run orchestrator to resume it. | Write down that the user's session, as orchestrator, resumes it; under director mode, a background orchestrator or the next roadmap pass does. | prose + plugin |
| B7 | `skills/director/references/coordinator-tiers.md`, `scripts/coordinator-tiers.mjs` | "Coordinator tier" is now orchestrator placement. | Rename in prose. Rename the files at the next plugin major version, or keep them with a pointer. | plugin |
| B8 | `skills/director/SKILL.md`, `agents/director.md`, `hooks/director-*.mjs` | The bare "director" names the layer, the mode and the profile. | Qualify each use where the context does not settle it. | plugin |
| B9 | `director-watch.mjs` | It reacts only to director-profile `runs_read` results. | Retarget it to `decisions_read` and the `decision.raised` event. | hook |

## C. Code names kept on purpose

| # | Where | Decision |
|---|-------|----------|
| C1 | `RunSeat.COORDINATOR`, `RunPermissions`, `coordinatorToken`, `coordinatorSeenAt`, `coordinator_seen_at`, `coordinatorStalled` | **Revised 2026-11-23.** Rename to the proper terms now (`orchestratorToken`, `orchestratorSeenAt`, `orchestratorStalled`, `RunSeat.ORCHESTRATOR`). Request fields accept the old names as input aliases. 4.x responses carry both keys. Old names stay listed as deprecated in 4.x and are removed in 5.0. Kotlin symbols are a plain rename. Never rename the column, because a migration for a name only is not worth it. The same thin-adapter pattern applies to the old `runs_*` decision ops once `decisions_*` lands. |
| C2 | `SeatDefinition.COORDINATOR_WRITABLE_SEAT`, seat name `orchestrator` | The seat name now matches the model. Rename the constant (for example `ORCHESTRATOR_WRITABLE_SEAT`), which is internal and has no wire effect. |
| C3 | `ToolProfile.DIRECTOR`, `/mcp/director` | Keep them. The profile is the director's own tool set and is no longer where answers come from (A1, A3). |

## S. Seat names (server, schema validation)

The decision is to keep the `orchestrator` seat. Under the two-layer model its name says who writes its notes. These rows stop it, or a layer name, from being misused as an ordinary dispatched seat.

| # | Where | What disagrees | Fix shape | Kind |
|---|-------|----------------|-----------|------|
| S1 | `SeatDefinition.kt:29-33`, `YamlSchemaParser` | `orchestrator` has meaning on the server but is not listed as reserved the way `unowned` is. Nothing says no agent is dispatched to it. | Document `orchestrator` as a reserved seat name next to `unowned` in the KDoc, `config-reference.md` and manage-schemas `config-format.md`. | server + docs |
| S2 | `SeatDefinition` fields on an `orchestrator` seat | A seat carries `enters`, `readsExclude`, `skills`, `rules` and `transitions`. Seats carry no dispatch settings: dispatch lives in a trait's `dispatch:` map, so a dispatch profile cannot be set on a seat. `readsExclude`, a test-author blindness hint, makes no sense for the orchestrator. Check `enters` before ruling on it: the Delegated path has the orchestrator send `start` itself. | A load-time warning for `readsExclude` on the `orchestrator` seat. Decide on `enters` after checking the entry-seat mechanics. `skills` and `rules` stay allowed, since they are how a project adds conduct for the orchestrator's own notes. | server |
| S3 | Seat name `director` | Seat names are opaque, so a seat named `director` loads today. The director never writes notes (its altitude rule), so such a seat promises something the layer never does. | A load-time structural error, the same as for `unowned`. | server |
| S4 | Seat name `coordinator` | It is a retired concept word. As a seat name it would bring back the confusion the glossary removed. | A load-time warning, not an error, so an existing project config still loads. | server |
| S5 | `SeatDefinition.COORDINATOR_WRITABLE_SEAT` | It is named for the retired term. This is C2 above. | Rename it to `ORCHESTRATOR_WRITABLE_SEAT`. The constant is internal and has no wire effect. | server |

## D. Config vocabulary (decided on afb85c6a, 2026-11-23)

| # | Term | Decision | Where it lands |
|---|------|----------|----------------|
| D1 | `orchestration.mode` (`workflow`, `schema`, `off`) | Rename to `extensions.claude-code.steering`. The hooks still read the old key as a deprecated alias. Glossary entry: Steering. | Item `00f1dd53` under f2b1432b |
| D2 | `answerers: director-or-human` | Keep the name. It means "the user or the director". The server's test changes from "served on the director profile" to "a human, or a caller without a seat token whose credential carries the answer capability". | Reshaping root (decisions surface, A3) |
| D3 | `on_expiry: escalate` | Keep it. The glossary links it to escalation: expiry raises the severity, an overdue decision meets the escalation criteria, and the director hands it to the user. | Glossary only (done on PR #754) |
| D4 | "orchestration push via `advance` `transition`" in root `CLAUDE.md` | It reads as a layer. | Reword it to "push dispatch". |
| D5 | `DispatchProfile.kt:21,30-31` KDoc | "orchestrator" means any dispatcher. | Under the new model that is correct: a dispatching session is an orchestrator. No change needed. Recorded so a reviewer does not re-flag it. |

## E. Incremental delivery

Method words (epic, story, charter, increment, open question) are schema vocabulary, not Task Orchestrator concepts (glossary PR for item 3db76afd). The rows below use neutral terms; an incremental-delivery pattern for the manage-schemas library is where the method words live.

| # | Where | What disagrees | Fix shape | Kind |
|---|-------|----------------|-----------|------|
| E1 | run-wave and `next` | "ready" means unblocked and claimable, which is not the same as having its queue notes filled. | Use "refined" for the queue-notes state, and keep "ready" where it is. Add a refined filter and `excludeTypes` to `next`. | server + prose |
| E2 | `deep-codebase-audit`, the slice-first layout | "slice" is taken. | No tool word is needed: a schema may call its items stories. Keep "slice" for the audit and the layout. | prose |
| E3 | The manage-schemas example `feature-implementation` | It uses an `auto` lifecycle with a full breakdown up front, which works against a tree that keeps changing. | An opt-in `incremental-delivery` pattern in the manage-schemas library, where epic, story and charter are its own types and notes. | schema example |
| E4 | `planning-standards/SKILL.md:46-51` | "Resolve every open choice before work starts" makes decide-first universal. | Classify each open choice instead: it blocks this item, it belongs to a later item, or it is a reversible assumption. | engineering skill |
| E5 | `agents/implementer.md:27-29` | Every raise blocks, and the text says "do not implement the alternative". | A bundled seat-default rule: proceed on a stated reversible assumption with a non-blocking raise, and block only when the choice is irreversible or changes the acceptance criteria. | bundled rule |
| E6 | `post-plan-workflow/SKILL.md:68,77` | Routing is all or nothing: a single unrefined leaf hand-dispatches the whole plan. | Route the refined subset and leave the rest in the backlog. | plugin |
| E7 | `TransitionTable.kt:96-103` | Review has no send-back trigger, and `reopen` works only from terminal. | A send-back path for acceptance. | server |

## K. Knowledge base (journey 8, 2026-11-24)

Keeping a knowledge base on the knowledge endpoint is a first-class journey. Evidence and line numbers: [evidence/journey-knowledge-base.md](evidence/journey-knowledge-base.md) (leaks KL1-KL29); requirements R35-R50 and the guard rails are on the Journey Config Fit page. Development stays the design centre; these rows keep its words from shutting the knowledge base out.

| # | Where | What disagrees | Fix shape | Kind |
|---|-------|----------------|-----------|------|
| K1 | `GLOSSARY.md` opening line | "Work items move through gated phases" frames the whole tool as phased building. | Say that items may be lifecycle-free, and that phases belong to types that have a lifecycle. | glossary |
| K2 | `GLOSSARY.md` Building item | Building is "the default", and its opposite is a question or a container; a knowledge entry is neither. | Word it as work a seat does, and say a lifecycle-free item is off that axis. | glossary |
| K3 | `GLOSSARY.md` (no entry) | Lifecycle-free items appear only under Building item's "Distinct from". | Candidate entry: Lifecycle-free item (`lifecycle: none`). Run the defining-project-terms entry test first. | glossary |
| K4 | `GLOSSARY.md` (no entry) | A parsed `[[...]]` reference and a `relates_to` edge are both called links. | Candidate entry: Link, distinct from a dependency edge. Run the entry test first. | glossary |
| K5 | `CurrentMcpServer.kt:652-658`, `ItemsWriteTool.kt:117-118` | The knowledge profile describes itself in terms of work items. | Knowledge-profile text names items, notes, links and documents. | server text |
| K6 | `items_write` `create` doc | "Create an item in queue": true only for a type with a lifecycle. | Say where a lifecycle-free item lands. | server text |
| K7 | `notes_write` `role`, note entry `role` | Every note names a phase, even on a lifecycle-free type. | Hold until a knowledge user needs it (R36); record the wording problem only. | server contract |

## Settled in this pass

- Certainty is judgement, not config. It lives in conversation; its outcomes are recorded as rulings, assumptions and acceptance grants.
- Acceptance becomes a gate in the server, opt-in per type, satisfied the same way by every surface; plan approval before the work is a conditional grant.
- Setup picks one pre-built, tested template from a reference library, domain first, and records which template and version it applied.
- Two domains: software development (primary) and keeping a knowledge base (first class, knowledge endpoint). Intake and operations is dropped.

- Orchestration mode and coordinator mode are one mode. An open run is an indicator.
- "Lead" and "run lead" were considered and dropped: both are the orchestrator.
- `decisions_read` has a single `list` select, with no `get`.
- The `orchestrator` seat stays as a reserved name. `director` is refused as a seat name and `coordinator` draws a warning.
