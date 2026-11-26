# Enforcement map

Every check Task Orchestrator applies and where it sits: server gates, plugin hooks, plugin scripts, and the restrictions that exist only as prose. Each row names the harm it prevents, where it is enforced today, and a verdict: **keep**, **narrow** (keep the check, shrink its reach to its harm), **move** (the check or sentence belongs in another layer) or **cut**. It is for contributors adding, changing or removing a check, and for reviewers asking whether a restriction is in the layer that holds the facts. It describes origin/main at `ce75ca2a8` on 2026-11-26 (PR #807 merged); verdicts not yet carried out are **target**. Owner decisions cited as "boundary decision N" come from the boundary analysis owner answers of 2026-11-26 (`plans/boundaries/README.md`, not tracked). Row ids are local to this document.

## How a check is placed

- A check sits in the layer that holds the facts it judges, at the moment of the harm. The server holds state (items, notes, seats, runs, tokens, decisions) and judges transitions and writes. A hook sees one host event and what the host reports (`agent_id`, `subagent_type`, the tool input). A script sees a run's plan and commits. None of them judges prose.
- A restriction that only prose states is advice. It is kept when it is a hint for a fact a layer enforces, moved when it belongs to a rule or reference the project can replace, and cut when nothing needs it.
- No enforcement machinery is added for a failure nobody has observed: the rule is stated and reported back (boundary decision 28 below).
- Every Claude Code hook fails open on its own error except the director guard, which fails closed under the director marker (`onFailure: block` in `claude-plugins/task-orchestrator/hooks/hooks-config.json`).

## Server gates (zone Z1 Server)

The transition gates are the `TransitionGate` values in `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/lifecycle/TransitionPolicy.kt`; their ids are `GateId` in `lifecycle/Decision.kt`. Paths below are relative to `current/src/main/kotlin/io/github/jpicklyk/mcptask/current/`.

| Id | Check | Harm it prevents | Where | Verdict |
|---|---|---|---|---|
| G1 | Ownership: a claimed item moves only for its claim holder | Two actors driving one item | `TransitionPolicy.OWNERSHIP` | keep |
| G2 | Seat gate: inside a not-closed run, on a member whose pinned schema is seat-aware, only the run token or the member's own seat token (within `enters` and `transitions`) may send a user trigger; a human credential is exempt | Work driven outside the run's authority; a seat moving an item it does not own | `TransitionPolicy.SEAT` (`lifecycle/TransitionPolicy.kt:116-125`) | keep. The exemption needs a credential of kind `human`: stdio and unauthenticated HTTP run as the local principal, an agent, so the user's own session is not exempt |
| G3 | Transition table: a trigger valid for the item's role | An impossible move | `TransitionPolicy.TABLE` | keep |
| G4 | Complete cascade only when every child is terminal; no cascade out of `blocked` | A parent closing early; a held item released by a child | `TransitionPolicy.WARRANT`, `TransitionPolicy.HOLD` | keep |
| G5 | Review pending, dependency, required notes, lease | Advancing past open review, a blocking edge, a missing note, a held resource | `REVIEW_PENDING`, `DEPENDENCY`, `NOTE`, `LEASE` gates | keep |
| G6 | Independence attestation: `independent_of` pairs | A seat reviewing its own work | `lifecycle/internal/domain/GatePredicate.kt` | keep |
| G7 | Note write rule: inside an open run on a seat-aware schema, a note is written only by its owning seat's token, an orchestrator note only by the run token; a human credential is exempt | Seats overwriting each other's record | `NoteSeatRule.check` (`note/internal/domain/NoteSeatRule.kt:30-42`) | keep |
| G8 | `assign` issues tokens only for declared seats (or the defaults on a seat-less item) and never for `coordinator` | Tokens for seats the schema lacks | `application/run/SeatService.kt` | keep; the plugin stops asking for fixed seat names |
| G9 | Strict config push and boot import: F1-F5 structural errors, reserved seat names, key ownership by layer | Ambiguous entry seats, cycles in `after`, keys in the wrong layer | `config/internal/wire/YamlSchemaParser.kt`; [current/docs/config-reference.md](../current/docs/config-reference.md) "Strict validation" | keep |
| G10 | Resume is checked for role and previous role only | n/a | `lifecycle/AdvanceService.kt:943-950` | **target**: a decision gate refuses `resume` while the item's blocking decision is pending ([0003](decisions/0003-role-neutral-decisions-surface.md)) |
| G11 | Acceptance gate | Delivered work closed unaccepted | not built | **target**: [0012](decisions/0012-acceptance-gate-note-form.md); its predicate checks that the answered acceptance decision carries the claims (commit, verify result, count of disposed findings) in `context` and `links`. Presence only: provenance, not proof ([0008](decisions/0008-acceptance-is-a-server-gate.md)) |
| G12 | Config vocabulary | A misspelled `purpose` silently turning a mechanism off; a seat that is not a seat | not built | **target**: an unknown `purpose` value is an error under strict parsing ([0015](decisions/0015-note-purpose.md)); a seat named `orchestrator` or `coordinator` warns for one release, then is refused ([0016](decisions/0016-seat-capability-and-orchestrator-notes.md)) |
| G13 | Refusal details | Teaching the run token as a seat | `seat_forbidden` lists `coordinator` in `allowedSeats` (`TransitionPolicy.kt`, `seatForbidden`) | **target**: `allowedSeats` lists real seats and the message names the run token; `seat_forbidden` and `note_owned_by_other` gain a `fixArgs` hint naming the run, so a skill on a run member knows to use the run token or hand the step to the run's orchestrator |

### The seat-less run scope: documented, not enforced

G2 and G7 apply only to a member whose pinned schema declares seats (`facts.seats.isEmpty()` in `TransitionPolicy.SEAT`; `seats.isEmpty()` in `NoteSeatRule.check`). On a seat-less member inside an open run, any caller may advance it and write its notes, and the member-seat tokens `assign` still issues there attribute work without authorising anything.

**Verdict (boundary decision 28): keep this scope and document it.** There is no observed misuse, and a check for an unobserved failure is machinery without data. The run contract, [current/docs/workflow-guide.md](../current/docs/workflow-guide.md) chapter 12 "Seats in a run", says seats are enforced "on a member whose pinned schema declares seats"; **target**, it states the consequence in one sentence as well. Reopen on an observed misfire.

## Hooks (zone Z2 Plugin mechanics, enforcement channel)

Paths are relative to `claude-plugins/task-orchestrator/hooks/`.

| Id | Check | Harm it prevents | Where | Verdict |
|---|---|---|---|---|
| H1 | Dispatch-model guard: an Agent call without `model` and `effort` is denied | A plugin seat agent (`model: inherit`, no `effort`) silently runs on the caller's model and default effort | `dispatch-model-guard.mjs` (`main`, `:39-58`). **Today** it fires on every Agent call wherever a config is located and steering is not `off`, including dispatches that are not Task Orchestrator work (skill evaluations, exploration agents); a user-level config would make it fire in every folder | **narrow** (boundary decision 18): deny only when `subagent_type` is a `task-orchestrator:*` agent or the `description` carries a Task Orchestrator label (the grammar `classifyAgentLabel` parses). The rule now reads "every Task Orchestrator dispatch names both" ([0001](decisions/0001-two-session-layers.md), done 2026-11-26); the core hook's model line and the dispatch hint follow |
| H2 | Decision guard: a subagent's `runs_write` `answer`, `ack`, `pause`, `resume` or `reissue` is denied | Driving and deciding mixing: an orchestrator answering its own decision | `decision-guard.mjs` (only a hook sees `agent_id`) | keep. [0001](decisions/0001-two-session-layers.md) lists all five ops |
| H3 | Director guard | The director's context filling with work content; the director driving work | `director-guard.mjs`; the one full description is [guard-coverage.md](../claude-plugins/task-orchestrator/skills/director/references/guard-coverage.md) | keep, with H4 narrowed |
| H4 | Director `Read`: a skill's files or an installed plugin's files only | Work content read under the director marker ([0014](decisions/0014-director-reads-skills-runs-plugin-scripts.md)) | `isPluginFile` closes plugin source inside the session's work tree, except the plugin the guard runs from (PR #807). `isSkillFile` (`director-guard.mjs:298-311`) still opens any folder under `skills/` that holds a `SKILL.md`, wherever it is, so plugin skill sources in the work tree stay readable | **narrow** (boundary decision 12): skill files under a plugin's source folder in the session's work tree close by the same work-tree test; `.claude/skills/**` and installed skills stay open; 0014 is amended in place |
| H5 | Seat skill gate: a subagent's file edit or phase-note write is denied until the skills its seat binds are loaded | A seat working without the method its schema (or the plugin default) binds | `seat-skill-gate.mjs` | keep; a project opts out with `skills: []` |
| H6 | Skill enforcement: a subagent's upsert is denied while the note's `skill:` is not loaded | The same, per note | `skill-enforcement.mjs` | keep |
| H7 | Skill enforcement, main session: advice when a body is short or matches a placeholder pattern | Thin notes | `skill-enforcement.mjs` (`PLACEHOLDER_PATTERNS`) | **narrow**: keep the "this note binds skill X" pointer, drop the length and placeholder test (a hook judging prose) |
| H8 | Phase guard: a seat's stop is sent back while its required notes are missing, at most `MAX_BLOCKS_PER_AGENT` times | A seat stopping with a progress report instead of finished notes | `phase-guard.mjs` | keep; its literal `test-manifest` fallback goes once the server's `missingBySeat` buckets are always present |
| H9 | SubagentStart pointer reaches only `implementer` and `reviewer` agent types | n/a (a reach gap) | `subagent-start.mjs:45`, `execution-mode.mjs` `isPhaseOwnerAgentType` | **move**: reach every seat agent; its retry lines go to the protocol rules, the hook keeps the generated code lists |
| H10 | Retrospective backstop and triage | An owed retrospective forgotten | `retro-backstop.mjs`, `retro-lib.mjs:586-588` | **narrow**: triage by the `closure-record` purpose; a type with none skips the triage (`minItems` alone) and the gap is reported, never read as a signal. See [run-execution.md](run-execution.md), "Retrospective" |
| H11 | Mod plugin retrospective spawn | Premature or duplicate retrospectives | `claude-plugins/task-orchestrator-mod/src/retro/` (fixed model, no effort, unlabelled) | **move**: model, effort, label and text from the plugin's libraries |
| H12 | `config-sync`: a rule file whose key fails the server's grammar is skipped; a lifecycle-free root gets no bundled rules | A push the server refuses; build rules on a root with no seats | `config-sync.mjs` | keep |
| H13 | `config-sync`: a warning when closure-note guidance asks for file lists or test results | Bloated closure notes | `config-sync.mjs:158-198`, by literal key and phrase | **cut**: the `manage-schemas` audit owns schema review |

## Scripts (zone Z2 Plugin mechanics)

Paths are relative to `claude-plugins/task-orchestrator/`.

| Id | Check | Harm it prevents | Where | Verdict |
|---|---|---|---|---|
| C1 | Run profile shape; every `postRun[]` entry must name `step`, `when` and `rule` | A malformed profile planning a broken run | `scripts/run-planner-lib.mjs` `validateSnapshot` (`:367` for `rule`) | **narrow**: shape checks stay; `rule` stops being required once `postRun[].skill` exists (at least one of the two) |
| C2 | Plan refusals (scratchpad, worktree and branch pairing, method and seat caps, empty admission, size cap); `--out` never overwrites a launched run's plan; the snapshot refuses a failed or unbounded read | A plan that cannot run; losing a live plan; planning on partial state | `scripts/run-planner-lib.mjs`, `scripts/lib/snapshot-builder.mjs` | keep |
| C3 | Verify: item tag, `Seat:` trailer, owned paths, refs; text encoding | Commits the resume and attribution checks cannot place; cross-item edits; mangled files | `scripts/run-exec-lib.mjs` | keep |
| C4 | Verify: a test-author commit before the implementer's last is a finding | An order the schema declared was not followed | `scripts/run-exec-lib.mjs:1436-1447`, with fixed roles | **narrow**: compare against the plan's stage order from `after` |
| C5 | Verify: planner sweep not reported or not disposed; test plan scenarios missing from the manifest | An incomplete sweep; uncovered scenarios | `scripts/run-exec-lib.mjs:1466-1495`, always on | **narrow**: only when the stage's resolved rules ask for that output (`ruleOutputs`, [run-execution.md](run-execution.md)) |
| C6 | Reseat argument checks; `adopt-notes` never renders a token-less upsert and never assigns an inserted stage | An unrecordable fix cycle; notes written outside the seat's ownership | `scripts/run-exec-lib.mjs` | keep |
| C7 | Reseat FIX CYCLE line tells every base seat to commit | n/a (a misfire: a planner or queue-seat base is told to commit) | `scripts/run-exec-lib.mjs:687` | **narrow**: only when the base seat writes |
| C8 | Label classes: a label in a reserved class is never a seat | Cost of a non-seat dispatch charged to a seat | `RESERVED_CLASSES` (`scripts/run-exec-lib.mjs:2781`), `scripts/lib/retro-metrics.mjs` | **narrow**: plugin classes plus the run profile's `postRun[].step` names; `coordinator` read as `orchestrator` |
| C9 | Every review-phase seat is a read-only reviewer; `writes` is the work phase | A reviewer fixing what it reviews | `seatRoleOf` (`hooks/lib/seat-rules.mjs:58`, review branch `:62`), `scripts/run-planner-lib.mjs:874` | **move** to the seat's declared `writes` ([seat-model.md](seat-model.md)) |
| C10 | Background orchestrator brief: never send note bodies, diffs or tokens, never answer a decision, never ask the user, never a standalone sleep | Content flowing up; deciding while driving; a hung script | `scripts/coordinator-tiers.mjs` (`tier2bPrompt` and its siblings) | keep; gains the RULES line of the orchestrator binding ([0017](decisions/0017-orchestrator-binding.md)) |
| C11 | Bug reports off unless the maintainer's environment variable is set | Issues filed upstream by users who never agreed | `scripts/lib/bug-report.mjs` | keep |

## Restrictions that are only prose

These bind nothing; each is either a hint for something enforced above, or conduct that belongs in a rule.

| Id | Text | Where | Verdict |
|---|---|---|---|
| T1 | "Do not begin exploring, reading code, or drafting your plan until this prerequisite completes" | `hooks/pre-plan.mjs:35` | **narrow**: "before drafting the plan"; the order of exploration is the user's |
| T2 | "Do not begin any implementation work until this completes" | `hooks/post-plan.mjs:35` | keep (materialize before implementing; what approval sets in motion becomes project config, [run-execution.md](run-execution.md)) |
| T3 | On `resource_unavailable`, do not fill more notes or retry at once | Core hook (`hooks/orchestration-context.mjs`) | keep: a hint for a server lease |
| T4 | An answer never resumes the item, never while pending, and a seat never resumes it | Core hook | **move** to `protocol.orchestrator`; once G10 lands it is the hint for that refusal |
| T5 | Never rewrite a seat-owned note | Core hook | **move** to `protocol.orchestrator` |
| T6 | Always pass `model` and `effort` on every Agent dispatch | Core hook (`MODEL_LINE`), `hooks/dispatch-hint.mjs` | keep, worded to H1's narrowed reach |
| T7 | Feature closure: one feature-level review, every finding disposed | Core hook (`PRINCIPLE_FEATURE_CLOSURE`), run-wave post-run Step 7b | **move** to the conduct default `feature-closure` on the orchestrator binding |
| T8 | No emoji unless the user asks; status presentation | Core hook | **move** to the shared presentation reference that the rendering skills cite; an output style overrides it |
| T9 | Schema steering: no tiers, no process the schema does not call for | Core hook | keep: it defines the `schema` steering mode |
| T10 | "Do not retry or spin" | `hooks/subagent-start.mjs` | **move**: the protocol rules own it (H9) |
| T11 | Never request any later transition | `bundled-rules/protocol.in-phase-seat.md`, `protocol.entry-seat.md`, `protocol.read-only-agent.md` | **narrow** (boundary decision 5): "send only the triggers your dispatch lists"; the dispatch lists the effective `transitions` |
| T12 | The test author and the planner never call `advance` | `agents/test-author.md:32`, `agents/planner.md` | **cut**: G2 and the protocol rule own it |
| T13 | Personal scope: do not pass `ancestorId` | `hooks/session-start.mjs` | keep (the mod must follow the same scope) |
| T14 | Director rule re-injected after compaction; director watch "do not push these ids again" | `hooks/director-context.mjs`, `hooks/director-watch.mjs` | keep |
| T15 | A `not_found` for the seat's protocol rule is fatal | `hooks/seat-skill-hint.mjs` | keep |

## The `headless-iteration` bypass

`TASK_ORCHESTRATOR_MODE=headless-iteration` marks a headless ralph iteration. Ralph sets it on every `claude -p` spawn (`buildIterationEnv`, `claude-plugins/task-orchestrator/scripts/ralph-lib.mjs`) and spawns with `--permission-mode bypassPermissions` (`ralph-lib.mjs:76`, `:342`). The test is `isHeadlessIteration` (`hooks/execution-mode.mjs:18`), exact and case-sensitive.

- **Returns early on it:** the dispatch-model guard (H1), the director guard and its context and watch hooks (H3), the phase guard and its recorder (H8), SubagentStart (H9), the retrospective trigger, backstop and subagent-stop hooks (H10), the dispatch hint, and the context hooks behind `suppressionReason` (session start, the core hook, pre-plan, post-plan).
- **Does not check it:** the decision guard (H2), the seat skill gate (H5) and skill enforcement (H6), which act only inside a subagent call and so still apply to subagents an iteration dispatches; and `config-sync`.

**Verdict: keep, documented.** One environment variable separates a guarded session from one with most guards off. That is a design choice for unattended draining, not a misplacement. **Target**: the ralph documentation states which guards it turns off. A standing-work policy for an unattended drain (what such a session may and may not do) is held until a project declares one.
