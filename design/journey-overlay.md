# Journey overlay

The user journeys walked step by step against the target architecture, as the evidence that the architecture is well designed: every step either crosses its zone boundaries through a declared seam (fits), needs a seam or decision nobody has supplied (gap), or works only because one zone holds another's responsibility (leak). It holds the method, the verdicts per journey and stage, what changed when the owner answered the boundary decisions and simplified the orchestrator's representation, and the open items as a burn-down list. It is for whoever plans or reviews a work package from [architecture-breakdown.md](architecture-breakdown.md), and for whoever changes a seam and must show no journey breaks. It describes the state of 2026-11-26 (origin/main `ce75ca2a8`) and the agreed target; the verdicts grade the target, and a step that fits there is not built today unless this document says so.

The architecture graded here is [target-architecture.md](target-architecture.md), with its seams in [seams.md](seams.md), the seat model in [seat-model.md](seat-model.md), config in [config-layering.md](config-layering.md), runs in [run-execution.md](run-execution.md) and checks in [enforcement-map.md](enforcement-map.md). The journeys are the four sequence diagrams in [journey-sequences.md](journey-sequences.md) (step numbers are the diagrams' own) and the eight journeys of the [Strategic Journey Map](user-journey-map.html) at stage level. Terms follow [GLOSSARY.md](../GLOSSARY.md).

Owner decisions are cited by their number in the boundary analysis owner answers of 2026-11-26 (`plans/boundaries/README.md`, not tracked), as revised after the simplicity review; where a decision has a record, the record is linked instead. The owner's decisions on pass 10 of that analysis (journey gap closure, 2026-11-26) are cited as G1 to G12. Path prefixes: `KT/` is `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/`; `PLUG/` is `claude-plugins/task-orchestrator/`.

## How to read it

- **Fits.** Every zone crossing in the step goes through a seam of the target, and every decision that seam rests on is taken. A fit that depends on an unbuilt seam is listed under "Closed by a decision, open until its package lands" until that package lands.
- **Gap.** The step needs a seam or a decision that no accepted record, owner answer or package supplies, or one the owner held or did not take. A gap is not a defect of the target; it is the target saying "nobody owns this yet".
- **Leak.** Even in the target, the step works only because one zone holds another zone's responsibility: plugin text deciding a project's process, a mechanics skill doing a gate's job. A leak is a defect of the target.
- **Rests on** names the seam, record, owner decision or package the verdict depends on. When that thing changes, the step is walked again.

## Method

1. Take the target as the zones and matrix of [target-architecture.md](target-architecture.md), the seams of [seams.md](seams.md), the accepted records in [decisions/](decisions/README.md) and the packages of [architecture-breakdown.md](architecture-breakdown.md).
2. For each step, name the acting zone, the seam that carries the project's choice, and the server gate that checks it. Grade it fits, gap or leak by the rules above.
3. An overlay proposal (a seam the walk itself found missing) counts as a gap until the owner accepts it. Once accepted, the step fits in the target and moves to the burn-down list until its package lands.
4. Re-run after each package lands: re-walk the steps that package names in the burn-down list, move the ones that now fit today, and re-walk every step whose "Rests on" names a seam the package changed. Re-run the knowledge journey (journey 3 and stages 8.x) when anything held in "Held until evidence asks" is released.

The first walk graded the target before the owner answered its decisions. This revision re-grades it under the owner's answers and under the simplicity review that followed: the orchestrator is never a seat ([0016](decisions/0016-seat-capability-and-orchestrator-notes.md)), a seat declares only `writes` (no `kind`), the acceptance claims travel as evidence on the acceptance decision ([0012](decisions/0012-acceptance-gate-note-form.md), amended), the orchestrator binding sits at root and trait level only ([0017](decisions/0017-orchestrator-binding.md)), and there are two rule classes ([0018](decisions/0018-content-placement.md)). A third grading applies the pass 10 decisions G1-G12, all taken as recommended: they close every undecided gap and two holds, correct the basis of three steps, and keep three holds with reopen triggers anyone can observe.

## Results

| Source | Steps | Fits | Gaps | Leaks | Before pass 10 (fits, gaps, leaks) | First walk (fits, gaps, leaks) |
|---|---|---|---|---|---|---|
| Journey 1: plan acceptance without plan mode | 25 | 25 | 0 | 0 | 24, 1, 0 | 21, 3, 1 |
| Journey 2: light touch | 27 | 27 | 0 | 0 | 26, 1, 0 | 24, 2, 1 |
| Journey 3: keep a knowledge base | 29 | 27 | 2 | 0 | 23, 6, 0 | 23, 6, 0 |
| Journey 4: build to learn | 22 | 22 | 0 | 0 | 20, 2, 0 | 18, 4, 0 |
| Map stages | 62 | 60 | 2 | 0 | 52, 10, 0 | 43, 15, 4 |
| **Total** | **165** | **161** | **4** | **0** | 145, 20, 0 | 129, 30, 6 |

What the results show:

1. **No leak is left in the target.** The six leaks of the first walk had two causes, and the owner took the decision for each: what plan approval sets in motion becomes project config (owner decision 16; journey 2 step 14 and stages 2.4, 3.2, 6.5, 7.3), and the acceptance grant's claims become evidence the gate checks for on the answered decision ([0012](decisions/0012-acceptance-gate-note-form.md); journey 1 step 17).
2. **The simplicity review changed no verdict.** Every fit that rested on the orchestrator seat, `kind` or a token-borrowing server rule still fits on the simpler representation. The steps whose basis changed are listed in the next section.
3. **Every remaining gap is an accepted hold.** The four gaps left rest on three holds that pass 10 decision G11 keeps: graph, rank or traversal `next` (stage 7.4), config delivery on the knowledge profile off Claude Code (journey 3 step 5), and a root-wide orphan and wanted-page read (journey 3 step 23, stage 8.6). Each now names a reopen trigger anyone can observe. No step waits on an owner decision.
4. **Pass 10 closed sixteen gaps with no new enforcement and one new server read.** The read is S63 (declared types on the knowledge profile, P8). Everything else rests on seams that exist or are already planned: an answered decision, note purposes, a note's `skill:`, `related`, a rule document, ralph's arguments and host scheduling. The verdicts it flipped and the bases it corrected are listed below.
5. **The seat and run machinery is sound.** No seat, run, review-lane, fix-cycle or orchestrator-note step leaks or gaps. Every crossing is a served fact (`enters`, `transitions`, `writes`, each note's computed owner) or a declared list (seat `rules:` and `skills:`, the orchestrator binding).

### Fits whose basis changed with the simplicity review

| Steps | First walk rested on | Now rests on |
|---|---|---|
| Journey 1 steps 4-6; journey 4 step 1; stages 1.4, 6.2 | The `orchestrator:` block at root, schema and trait level | The orchestrator binding at root (and trait) level ([0017](decisions/0017-orchestrator-binding.md)). The root binding is what reaches the main session before an item exists; no step used the dropped schema level |
| Journey 1 step 12; journey 2 step 18 | Seat `writes` plus `kind` | Seat `writes`, with rule and skill defaults keyed by seat name ([0016](decisions/0016-seat-capability-and-orchestrator-notes.md)). Fits on the dogfood seat names; a custom-named seat declares its own `rules:`, `skills:` and dispatch override |
| Journey 1 step 12 (rules resolved) | Three rule classes | Two rule classes: protocol rule (including `protocol.commit` for every writing seat) and seat default ([0018](decisions/0018-content-placement.md)) |
| Journey 1 step 17 | A separate claims note beside the answered decision | The claims (commit, verify command and result, count of disposed findings) carried in the acceptance decision's `context` and `links`; the gate checks their presence on the answered decision, as provenance ([0012](decisions/0012-acceptance-gate-note-form.md)) |
| Journey 1 steps 18, 21, 23 (J-F1) | A server rule letting an entry skill act as the run's orchestrator, with a new refusal code | No server rule. The accept skill uses the run token from the run files, or hands the step to the run's orchestrator; `seat_forbidden` and `note_owned_by_other` gain a `fixArgs` hint naming the run ([0016](decisions/0016-seat-capability-and-orchestrator-notes.md)). The acceptance note is an orchestrator note, which the run token may write |
| Journey 1 step 22; journey 4 step 17; stages 3.3, 3.5 | Decision 9 pending | Taken: [0003](decisions/0003-role-neutral-decisions-surface.md) is the destination (`decisions_*` on every profile that needs them, authority from the class and a credential's `decide` capability). Today director mode uses two connections, the decision ops being served on the director profile only ([0002](decisions/0002-director-on-orchestrator-tools.md)) |
| Journey 2 steps 16-17; stage 7.3 (ungated type) | 0008's "merge is armed only on acceptance" leaving an ungated type with no trigger | For an ungated type, merge is armed by the project's own merge step, as today; 0008's sentence is narrowed to gated types (owner decision 17, unchanged by the review) |

### Verdicts that flipped because the owner took a decision

| Steps | First walk | Now | Closed by |
|---|---|---|---|
| Journey 1 steps 2, 8 | gap | fits | Owner decision 26: the skill that agrees a plan (shape, or the orchestrator) stores it as a plan document before `create_tree` on every host; plan mode stays the fast path; shape plans a single known change too |
| Journey 1 step 17 | leak | fits | [0012](decisions/0012-acceptance-gate-note-form.md), amended: claims as evidence on the answered decision |
| Journey 2 step 8; stage 7.2 | gap | fits | [0015](decisions/0015-note-purpose.md): a note with purpose `ruling` records the sizing outcome that [0007](decisions/0007-certainty-is-judgement.md) requires; the sizing itself stays the orchestrator's judgement |
| Journey 2 step 14; stages 2.4, 3.2, 6.5, 7.3 | leak | fits | Owner decision 16: approval routing is an `extensions.claude-code` key, default today's behaviour, set by each template |
| Journey 4 steps 5, 18; stages 1.2, 6.1 | gap | fits | [0015](decisions/0015-note-purpose.md): purposes `goal` and `ruling`, so a method-neutral skill finds the goal note and the ruling by job, not key; the ruling's tie to its decision is the answer's evidence links ([0003](decisions/0003-role-neutral-decisions-surface.md)) |
| Stage 4.8 | gap | fits | Owner decision 29: the stage reads "cost by meter and model, against the run's sizing tier"; no planned-cost field |
| Stage 5.4 | gap | fits | [0018](decisions/0018-content-placement.md): the layer test leaves this repository's `CLAUDE.md` for a record and a shipped mechanics reference that tune and manage-schemas read |

### Fits whose basis changed with pass 10

| Steps | Rested on | Now rests on |
|---|---|---|
| Journey 2 step 16 | Host integration: the grant read from Claude Code's permission mode | The grant offered as one plain choice and recorded in the user's own words, on every host (G1; [0008](decisions/0008-acceptance-is-a-server-gate.md), [0012](decisions/0012-acceptance-gate-note-form.md), amended). The step is harness-neutral |
| Journey 3 steps 21-22 | One board with a knowledge view (P3), which assumed `report` reads the knowledge profile does not serve (every `report` select is director-only today, `KT/report/internal/wire/ReportRollupSelect.kt:45`) | The knowledge view composed from the knowledge profile's own list reads, with no orphans while the orphan read is held (G8; P3) |

### Verdicts that flipped with pass 10

| Steps | Before | Now | Closed by |
|---|---|---|---|
| Journey 1 step 7 | gap | fits | G1: a grant counts only when the user's own words say so, on every host, and the orchestrator records them ([0008](decisions/0008-acceptance-is-a-server-gate.md), [0012](decisions/0012-acceptance-gate-note-form.md), amended) |
| Journey 2 step 25; journey 3 step 29; stages 7.5, 8.8 | gap | fits | G2: learn reads the docs, glossary and design from GitHub at the installed version, plus the user's served schema and config ([0019](decisions/0019-learn-reads-upstream-sources.md), S68); on the knowledge profile with G3 |
| Journey 3 step 9; stage 8.2 | gap | fits | G3: S63 reopened, declared types readable on the knowledge profile |
| Journey 3 step 20; stage 8.5 | gap | fits | G6: the knowledge template declares a synthesis note beside the page, its `skill:` binding the method (S03) |
| Journey 3 step 27; stage 8.7 | gap | fits | G10: "nothing deletes without a yes" is knowledge mechanics text; G7: the maturity rule (S69) gives "archive" a meaning for items. The binding hold stays ([0017](decisions/0017-orchestrator-binding.md)) |
| Journey 4 step 12; stage 6.4 | gap | fits | G4: the building seat records kept and thrown commits in its `finding`, the orchestrator writes the `ruling`, and a project rule removes thrown code |
| Journey 4 step 21; stage 6.7 | gap | fits | G5: shape re-walks the ruling's subtree and the `linked_from` hits of `items_read` `related` (S62 reader) |
| Stage 3.7 | gap | fits | G9: carried by ralph's arguments and host scheduling, with the `headless-iteration` bypass documented in ralph's text; only a persisted policy stays held |

## J-F1 and the resume gate

Two server facts the journeys depend on, checked against the code.

**J-F1: accepting an open-run member needs the run token, and the user's own session is not exempt.** The seat gate exempts a human caller (`KT/lifecycle/TransitionPolicy.kt:122`), and so does the note rule (`KT/note/internal/domain/NoteSeatRule.kt:40`). A caller is human only with a credential of kind `human` and no seat token (`KT/platform/call/CallContext.kt:97-101`), and that kind comes only from a credential source (`KT/platform/call/Principal.kt:8-11`). Stdio, HTTP `/mcp` in local mode and REST with `API_AUTH_MODE=none` all run as the local principal, of kind `agent` (`KT/platform/call/LocalPrincipal.kt:5-19`). So on every default transport the user's session is refused `seat_forbidden` when it advances an open-run member of a seat-aware type (`TransitionPolicy.kt:124`), and `note_owned_by_other` when it writes the acceptance note, which only the run token may write (`NoteSeatRule.kt:61`). The run token may send any trigger on any member of its run (`TransitionPolicy.kt:327`). The target keeps those refusals: the accept skill uses the run token, which the user's session in orchestration mode can read from the run files because it opened the run, and in director mode it hands the step to the run's orchestrator ([0016](decisions/0016-seat-capability-and-orchestrator-notes.md)). Journey 1 steps 18, 21 and 23 rest on that skill behaviour, not on any new server rule.

**The resume gate is missing today.** Item `resume` is checked only for the blocked role and a restorable previous role (`KT/lifecycle/internal/domain/TransitionTable.kt:106-110`; the refusal messages at `KT/lifecycle/AdvanceService.kt:943-950`), and the gate set has no decision gate (`KT/lifecycle/Decision.kt:10`). So `resume` is not refused while the item's blocking decision is pending; only plugin prose says not to (`PLUG/hooks/orchestration-context.mjs:46`). [0003](decisions/0003-role-neutral-decisions-surface.md) already accepts the refusal, and package P2 carries it. Journey 4 step 22 and stage 6.6 fit in the target on that gate.

## Journey 1: plan acceptance on a host without plan mode

| Steps | Verdict | Rests on |
|---|---|---|
| 1 | fits | - |
| 2 | fits (was gap) | Shape plans a single known change (owner decision 26; P7) |
| 3 | fits | Queue notes found by purpose ([0015](decisions/0015-note-purpose.md); P10) |
| 4-6 | fits | Orchestrator binding at root carries the planning method ([0017](decisions/0017-orchestrator-binding.md); P11) |
| 7 | fits (was gap) | A grant counts only when the user's own words say so, on every host; the orchestrator records them as the answer with the conditional option (G1; [0008](decisions/0008-acceptance-is-a-server-gate.md), [0012](decisions/0012-acceptance-gate-note-form.md), amended; P4, P7) |
| 8 | fits (was gap) | Plan store on every host (owner decision 26; P7) |
| 9 | fits | `create_tree` validation |
| 10-11 | fits | Relayed answer on the decisions surface, class `answerers`, credential (P2) |
| 12 | fits | Seat facts, `writes`, effective `transitions` in the dispatch, defaults by seat name, two rule classes ([0016](decisions/0016-seat-capability-and-orchestrator-notes.md), [0018](decisions/0018-content-placement.md); P9, P12) |
| 13 | fits | `enters`; seat gate, note gate |
| 14 | fits | Review lanes from declared seats (P9) |
| 15-16 | fits | Notes by purpose (P10) |
| 17 | fits (was leak) | Claims as evidence on the acceptance decision, presence checked by the gate ([0012](decisions/0012-acceptance-gate-note-form.md); P4) |
| 18 | fits | J-F1: the accept skill uses the run token (P7); acceptance gate (P4); seat gate |
| 19-20 | fits | Accept card (P7), rendered through the shared presentation reference (owner decision 23) (P13) |
| 21 | fits | Relayed answer (P2); acceptance note as an orchestrator note written with the run token (P9, P7) |
| 22 | fits | Decisions surface on every profile, `decide` capability ([0003](decisions/0003-role-neutral-decisions-surface.md); P2) |
| 23 | fits | Run token; fix cycle from seat facts (P7, P9) |
| 24 | fits | Acceptance gate (P4) |
| 25 | fits | `postRun[].when: accept` (P4) and `postRun[].skill` (P13) |

## Journey 2: light touch

| Steps | Verdict | Rests on |
|---|---|---|
| 1-5 | fits | Setup and the template record, steering in the template (P6) |
| 6 | fits | Steering; the orchestrator binding's resolved rules line at session start (P11) |
| 7 | fits | - |
| 8 | fits (was gap) | Sizing outcome recorded under purpose `ruling` ([0015](decisions/0015-note-purpose.md); P10); sizing is judgement ([0007](decisions/0007-certainty-is-judgement.md)) |
| 9-13 | fits | Host plan-mode events (host integration) |
| 14 | fits (was leak) | Approval routing as project config (owner decision 16; P6) |
| 15 | fits | `create_tree` |
| 16 | fits | The grant offered as one plain choice and recorded in the user's own words; harness-neutral (G1; P4, P7) |
| 17 | fits | Decisions surface, acceptance gate (P2, P4) |
| 18 | fits | `writes`, defaults by seat name, dispatch guard ([0016](decisions/0016-seat-capability-and-orchestrator-notes.md); P9) |
| 19-20 | fits | Seat-less type, no run; phase gates |
| 21-23 | fits | `next` returns startable work ([0004](decisions/0004-next-means-startable.md); P1) |
| 24 | fits | - |
| 25 | fits (was gap) | learn fetches Task Orchestrator's docs, glossary and design at the installed version, plus the user's served schema and config (G2; [0019](decisions/0019-learn-reads-upstream-sources.md); S68; P7) |
| 26-27 | fits | Template record compared on upgrade (P6) |

## Journey 3: keep a knowledge base

| Steps | Verdict | Rests on |
|---|---|---|
| 1-4 | fits | Setup on the knowledge profile (P6) |
| 5 | **gap** | Config delivery off Claude Code: the knowledge profile serves no `config`, and only a Claude Code hook pushes it over REST. Held (G11): reopen when a knowledge-profile connection whose MCP `initialize` client name is not Claude Code shows in server logs or a user report |
| 6 | fits | Profile-aware hooks (P5) |
| 7-8 | fits | - |
| 9 | fits (was gap) | Declared types readable on the knowledge profile: `items_read` `schema` with no type (S63 reopened, G3; P8) |
| 10-14 | fits | - |
| 15 | fits | Relation words held by the breakdown's decision |
| 16-19 | fits | - |
| 20 | fits (was gap) | A synthesis note the knowledge template declares beside the page, its `skill:` binding the method (S03; G6; P5, P6) |
| 21-22 | fits | One board; the knowledge view composed from knowledge-profile list reads, no orphans while the orphan read is held (G8; P3) |
| 23 | **gap** | Orphans and wanted pages have no root-wide read. Held (G11): reopen when a knowledge user asks for orphans or wanted pages, recorded as unmet |
| 24-26 | fits | - |
| 27 | fits (was gap) | "Nothing deletes without a yes" as knowledge mechanics text (G10; P5); "archive" is the maturity rule's archive tag (S69, G7; P5, P6). A binding for a knowledge agent's own conduct stays held ([0017](decisions/0017-orchestrator-binding.md), Reopen when) |
| 28 | fits | - |
| 29 | fits (was gap) | As journey 2 step 25, with the root's types read through S63 on the knowledge profile (G2, G3; P7, P8) |

The knowledge-base guard rails on [journey-config-fit.html](journey-config-fit.html) all hold: the target adds no phase, gate, seat, decision class or detector to this domain.

## Journey 4: build to learn

| Steps | Verdict | Rests on |
|---|---|---|
| 1 | fits | Orchestrator binding at root carries shape's classification method ([0017](decisions/0017-orchestrator-binding.md); P11) |
| 2-3 | fits | - |
| 4 | fits | `create_tree` dependency edges to existing items |
| 5 | fits (was gap) | Goal note by purpose `goal` ([0015](decisions/0015-note-purpose.md); P10) |
| 6-7 | fits | `next` ([0004](decisions/0004-next-means-startable.md)) |
| 8 | fits | Dispatch guard, narrowed to Task Orchestrator dispatches |
| 9-11 | fits | Seat facts; seat gate, note gate |
| 12 | fits (was gap) | Keep or throw: the building seat records kept and thrown commits in its `finding` (an `implementation-standards` section, P10; template guidance, P6); the orchestrator writes the `ruling` from that pointer (P7); a project rule removes thrown code (G4) |
| 13-16 | fits | Decisions surface (P2) |
| 17 | fits | Decisions surface on every profile ([0003](decisions/0003-role-neutral-decisions-surface.md); P2) |
| 18 | fits (was gap) | Ruling by purpose `ruling`; evidence links on the answer ([0015](decisions/0015-note-purpose.md); P2, P10) |
| 19 | fits | Acceptance gate where the type opts in (P4) |
| 20 | fits | - |
| 21 | fits (was gap) | Shape re-walks the ruling's subtree and the `linked_from` hits of `items_read` `related`; a dedicated read only after an observed miss (S62 reader, G5; P7, links P8) |
| 22 | fits | Resume gate (P2); missing today |

## The journey map at stage level

Moments: I Intend, D Decide, A Accept, M Improve, L Look, E Learn, S Setup, G between the moments (the agent).

| Stage | Moment | Verdict | Rests on |
|---|---|---|---|
| 1.1 Set up | S | fits | Setup (P6) |
| 1.2 Vision to first pieces | I | fits (was gap) | Goal note by purpose (P10) |
| 1.3 What first | I | fits | Dependency edges; no rank ([0013](decisions/0013-no-sibling-rank-yet.md)) |
| 1.4 Define done | I | fits | Planning bar through the root orchestrator binding (P11) |
| 1.5 Build | G | fits | Run-wave over the seat model (P9) |
| 1.6 Progress | L | fits | Board with the decision inbox (P2, P3) |
| 1.7 Ship v1 | A | fits | Acceptance gate, ship step (P4) |
| 1.8 Learn | M | fits | Retrospective by purpose `closure-record` (P10) |
| 2.1 Re-entry briefing | L | fits | Session start live state, board |
| 2.2 Prioritise | I | fits | Judgement over priority and edges |
| 2.3 Idea to plan | I | fits | Plan mode |
| 2.4 Build hands-off | G | fits (was leak) | Approval routing (owner decision 16; P6) |
| 2.5 Accept | A | fits | Accept, acceptance gate, decisions (P2, P4, P7) |
| 2.6 Release prep | A | fits | Ship step to the project's release skill (P4) |
| 2.7 Learn and evolve | M | fits | Retrospective, proposals |
| 2.8 Monthly health | I | fits | Audit skills, then shape |
| 3.1 Frame the initiative | I | fits | Plan mode, create-item, edges |
| 3.2 Launch | G | fits (was leak) | As 2.4 |
| 3.3 Stay free | D | fits | Director mode on the decisions surface (P2) |
| 3.4 Watch the board | L | fits | P3 |
| 3.5 Decisions | D | fits | Decisions surface, class, credential (P2) |
| 3.6 Steer | I | fits | Director hands work to orchestrators |
| 3.7 Drain unattended | G | fits (was gap) | Ralph's arguments and host scheduling; under `headless-iteration` most hooks return early, and only the decision guard, seat skill gate and skill enforcement still apply, to subagents ([enforcement-map.md](enforcement-map.md)), which ralph's text is to say (G9; P13). A persisted policy stays held |
| 3.8 Close and improve | M | fits | Retrospective per run |
| 4.1 Capture | I | fits | create-item |
| 4.2 Triage | I | fits | shape |
| 4.3 Health check | I | fits | Audit with scope and budget arguments |
| 4.4 Decide investment | I | fits | Judgement, then items |
| 4.5 Materialise | G | fits | create-item |
| 4.6 Untangle | L | fits | dependency-manager |
| 4.7 Dispatch | G | fits | Run-wave or director |
| 4.8 Close and learn | M | fits (was gap) | Cost by meter and model, against the run's sizing tier (owner decision 29) |
| 5.1 Run closes | M | fits | Retrospective mark, board |
| 5.2 Process health | M | fits | tune (P7) |
| 5.3 Decide proposals | M | fits | Improvement-proposal type |
| 5.4 Edit the process | M | fits (was gap) | Layer test in a record and a shipped reference ([0018](decisions/0018-content-placement.md); P12) |
| 5.5 Still fits? | M | fits | manage-schemas audit |
| 5.6 Did it work? | M | fits | Retrospective adoption line, `closure-record` |
| 5.7 Deep sweep | I | fits | Audit, then shape |
| 6.1 Frame | I | fits (was gap) | Goal note by purpose (P10) |
| 6.2 Shape | I | fits | Classification bound through the root orchestrator binding (P11) |
| 6.3 Decide shared calls | D | fits | Decisions surface (P2) |
| 6.4 Build to learn | D | fits (was gap) | As journey 4 step 12 (G4) |
| 6.5 Start | G | fits (was leak) | As 2.4 |
| 6.6 Decide as they surface | D | fits | Blocking raise; resume gate (P2) |
| 6.7 Adjust scope | I | fits (was gap) | As journey 4 step 21 (G5) |
| 6.8 Accept the node | A | fits | Acceptance gate (P4) |
| 6.9 Look back | M | fits | Retrospective per completed scope |
| 7.1 Set up | S | fits | Light template (P6) |
| 7.2 Say what you want | I | fits (was gap) | Sizing recorded as a `ruling` (P10) |
| 7.3 Agree the plan | I | fits (was leak) | Approval routing (owner decision 16); merge arming for an ungated type stays with the project's merge step (owner decision 17) |
| 7.4 Keep going | G | **gap** | Graph, rank or traversal `next`: held by [0004](decisions/0004-next-means-startable.md) and [0013](decisions/0013-no-sibling-rank-yet.md) (G11), reopened on their amended trigger |
| 7.5 Learn the words | E | fits (was gap) | As journey 2 step 25 (G2) |
| 7.6 Stay current | S | fits | Template record (P6) |
| 8.1 Set up | S | fits | Setup on the knowledge profile (P6) |
| 8.2 Capture | I | fits (was gap) | As journey 3 step 9 (G3) |
| 8.3 Link | I | fits | Relation words held |
| 8.4 Find | L | fits | search, related, links |
| 8.5 Synthesise | G | fits (was gap) | As journey 3 step 20 (G6) |
| 8.6 Revisit | L | **gap** | As journey 3 step 23: the digest shows recent and untouched entries; orphans and wanted pages stay held (G11) |
| 8.7 Prune and reshape | M | fits (was gap) | As journey 3 step 27 (G7, G10) |
| 8.8 Learn | E | fits (was gap) | As journey 3 step 29 (G2, G3) |

Per map journey (fits, gaps, leaks): J1 8, 0, 0; J2 8, 0, 0; J3 8, 0, 0; J4 8, 0, 0; J5 7, 0, 0; J6 9, 0, 0; J7 5, 1, 0; J8 7, 1, 0.

## Burn-down

### Open in the target

Each is an accepted hold: pass 10 decision G11 keeps it and names the sign that reopens it. None is closed by a package alone.

| Item | Steps | What reopens it |
|---|---|---|
| Graph, rank or traversal `next` | Stage 7.4 | Held by [0004](decisions/0004-next-means-startable.md) and [0013](decisions/0013-no-sibling-rank-yet.md) (amended): an `agent-observation` or proposal recording that the user had to name the next item because priority, complexity and edges could not express the order, or shape creating `blocks` edges that carry only order |
| Config delivery on the knowledge profile off Claude Code | Journey 3 step 5 | A knowledge-profile connection whose MCP `initialize` client name is not Claude Code, seen in server logs or a user report. The REST push documented by configure-server stays the workaround |
| Root-wide orphan and wanted-page read | Journey 3 step 23, stage 8.6 | A knowledge user asking for orphans or wanted pages, recorded as unmet in an observation or a learn or board request |

### Closed by a decision, open until its package lands

These fit in the target and not today. A row leaves this list when its package lands and the re-run confirms the steps.

| Package ([architecture-breakdown.md](architecture-breakdown.md)) | Steps it turns into fits today |
|---|---|
| P2 Decisions surface | Journey 1 steps 10-11, 21, 22; journey 2 step 17; journey 4 steps 13-17 and 22 (the resume gate); stages 1.6, 3.3, 3.5, 6.3, 6.6 |
| P3 One board | Journey 3 steps 21-22 (the knowledge view from knowledge-profile list reads, G8) |
| P4 Acceptance gate | Journey 1 steps 7 (the grant in the user's own words, G1), 17 (claims as decision evidence), 18, 23, 24, 25; journey 2 step 16 (G1); journey 4 step 19; stages 1.7, 2.5, 2.6, 6.8, 7.3 (ungated merge arming) |
| P5 Profile-aware hooks | Journey 3 steps 20 (write-up through the synthesis note, G6) and 27 (delete-needs-a-yes as mechanics text and the maturity rule, G10, G7); stages 8.5, 8.7 |
| P6 Setup and templates, with approval routing | Journey 2 steps 1-5, 14, 26-27; journey 3 steps 1-4, 20 (the synthesis note, G6) and 27 (the maturity rule, G7); journey 4 step 12 (`finding` guidance, G4); stages 1.1, 2.4, 3.2, 6.4, 6.5, 7.1, 7.3, 7.6, 8.1, 8.5, 8.7 |
| P7 Entry skills | Journey 1 steps 2 and 8 (plan store, single-change planning), 7 (the grant offered in the plan proposal, G1), 18-21, 23 (accept card, accept with the run token, claims raised as evidence); journey 2 steps 16 (G1) and 25 (learn's upstream sources, G2); journey 3 step 29 (G2); journey 4 steps 12 (the ruling from the `finding`, G4) and 21 (a ruling's dependents, G5); stages 5.2, 6.4, 6.7, 7.5, 8.8 |
| P8 Shape support on the server | Journey 3 steps 9 and 29 (declared types on the knowledge profile, G3); journey 4 step 21 (`derived_from` links); stages 6.7, 8.2, 8.8 |
| P9 Seat model | Journey 1 steps 12-14, 21, 23 (orchestrator notes in every schema, served owner, `fixArgs` hint); journey 2 step 18; stage 1.5 |
| P10 Note purpose | Journey 1 steps 3, 15-16; journey 2 step 8; journey 4 steps 5, 12 (the `implementation-standards` section, G4), 18; stages 1.2, 1.8, 6.1, 6.4, 7.2 |
| P11 Orchestrator binding | Journey 1 steps 4-6; journey 2 step 6; journey 4 step 1; stages 1.4, 6.2 |
| P12 Plugin defaults and rule classes | Journey 1 step 12 (rules resolved by two classes); stage 5.4 (the layer-test reference) |
| P13 Plugin boundary fixes | Stage 3.7 (ralph's text names the guards `headless-iteration` turns off, G9) |
| No package | Stage 4.8: the stage is reworded (owner decision 29). The retrospective reports cost by meter and model today (`PLUG/skills/session-retrospective/references/metrics.md`); setting it against the run's sizing tier is not built |

Steps named in neither list fit today on seams that already exist, or fit once P1, P3, P5 or P8 lands for work those packages held before pass 10; none of them carries an open decision.
