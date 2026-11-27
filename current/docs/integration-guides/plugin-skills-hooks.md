# Tier 4: Plugin — Skills and Hooks

## What You Get

- The hook scripts (everything registered in `claude-plugins/task-orchestrator/hooks/hooks-config.json`) that fire on session start, plan mode, plan approval, subagent launch and stop, run close, director mode and, when the REST API is configured, config sync and gate enforcement
- User-invocable skills as `/task-orchestrator:*` slash commands (see "User Skills" below)
- Internal skills that power the plan-mode pipeline and the phase-owner protocol
- The Agent-Owned-Phase Protocol injected into phase-owner subagents automatically (which subagents: `hooks/README.md`)
- Run-based orchestration: `run-wave` and `ralph` open server runs, `director` watches them (see "Runs, director mode and ralph" below)

## Prerequisites

- [Claude Code](https://claude.ai/code) CLI installed
- [Tier 1: Bare MCP](bare-mcp.md) setup complete, against a 4.x server

The 4.0 plugin needs a server of major version 4 that reports the `seats` feature. See "Compatibility with the server" below.

### Compatibility with the server

The plugin and the server are versioned together by major version. The 4.x plugin speaks the 4.0 tool surface (`items_read`, `advance`, `runs_write` and the rest of the registry) and needs a server whose major version is 4 and whose `GET /.well-known/mcp-task-orchestrator.json` lists the `seats` feature (`hooks/lib/server-compat.mjs`, `assessServer`).

| Plugin | Server | Result |
|--------|--------|--------|
| 4.x | 4.x with `seats` | Supported. |
| 4.x | 3.x, or 4.x without `seats` | `session-start` shows one warning line (when a REST API URL resolves and the server answers); `run-wave` `plan` refuses (exit 3, `server incompatible: ...`); a tool result that still has the 3.x shape shows one warning per session from `phase-guard-record` and `dispatch-hint`, which record and hint nothing. The other hooks fail open. |
| 3.x | 3.x | Supported (the `release/3.x` line). |
| 3.x | 4.x | Not supported: every 3.x call shape is gone, so the hooks fail open and the skills call tools that do not exist. |

To keep a 3.x server on the 3.x plugin, install the plugin from the `release/3.x` line: clone the repository and check out `release/3.x`; remove the 4.x marketplace first (`/plugin marketplace remove task-orchestrator-marketplace`, since both lines use that name); then run `/plugin marketplace add <checkout>` with the repository root, which holds `.claude-plugin/marketplace.json` and against which its `source` paths resolve. The marketplace serves the checkout's working tree, so keep that checkout on `release/3.x`.

---

## Installation

Two commands in Claude Code:

```
/plugin marketplace add https://github.com/jpicklyk/task-orchestrator
/plugin install task-orchestrator@task-orchestrator-marketplace
```

Verify with `/plugin list` — you should see `task-orchestrator` as enabled.

After editing plugin files, the plugin cache is version-keyed, so neither a marketplace update nor a remove and re-add refreshes it; refresh it as "Plugin Discovery and Cache Refresh" in [claude-plugins/CLAUDE.md](../../../claude-plugins/CLAUDE.md#plugin-discovery-and-cache-refresh) describes. For initial installation this is not required.

See [Quick Start](../quick-start.md) Step 4 for the full installation walkthrough.

### Two MCP entries

`/task-orchestrator:init` registers two entries next to each other in `.mcp.json`, and both keys contain `task-orchestrator` because the plugin's hook matchers key on that token:

| Entry | Profile | Serves |
|-------|---------|--------|
| the orchestration entry (for example `mcp-task-orchestrator`) | `orchestration` | the [orchestration profile](../api-reference.md#tool-profiles) |
| `mcp-task-orchestrator-director` | `director` | the [director profile](../api-reference.md#tool-profiles) |

The orchestration entry carries item, note, dependency, document and config work, phase transitions and claims, and the run ops of an orchestrator and its seats; not answer, ack, pause or resume. The director entry is where decisions are answered and acknowledged and runs are paused or resumed, and what director mode uses; a subagent or orchestrator raises a decision and never answers one.

An HTTP director entry is the orchestration URL with `/mcp/director` in place of `/mcp`; a stdio director entry is the orchestration command with `-e MCP_TOOL_PROFILE=director`. Pass `--no-director` to skip it. In credential mode the orchestration entry carries the `${TASK_ORCHESTRATOR_API_TOKEN}` header placeholder and the director entry carries `${TASK_ORCHESTRATOR_DIRECTOR_TOKEN}`, never a token. Details: `skills/init/SKILL.md` Step 2 and [fleet-deployment.md](../fleet-deployment.md).

### Plugin settings

The plugin reads its own settings from `extensions.claude-code` in `.taskorchestrator/config.yaml` (`steering`, `retrospective.mode`, `retrospective.minItems`, `retrospective.boundary`, `retrospective.github_feedback`). A top-level `orchestration:` or `retrospective:` block is ignored by the hooks and rejected by a 4.0 server on push. The old `orchestration.mode` key still works as a deprecated alias for `steering`. The keys, values and defaults are in [config-format.md](../../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#plugin-settings-extensionsclaude-code), and [config-reference.md](../config-reference.md#extensions) says who owns each key.

---

## Hooks

Hooks fire automatically after installation: they inject context at session start, guard dispatches and phase transitions, record skill use and nudge the retrospective. Each hook is fail-open except `director-guard`, which fails closed under the director marker.

What each hook does, when it is inert and how it is gated is owned by [`hooks/README.md`](../../../claude-plugins/task-orchestrator/hooks/README.md).

---

## Runs, director mode and ralph

A **run** is a server-side unit of work (`runs_write` / `runs_read`, see the Runs category of the [API reference](../api-reference.md)). The plugin has three consumers.

### Model and effort policy

Every dispatch (run-wave, `implement-wave`, ralph, orchestrators, retrospective extractors) takes its `model` and `effort` from `dispatch-policy.json`, through `scripts/lib/dispatch-policy.mjs`; a trait's `dispatch` profile overrides it per field; the audit phases take theirs from the policy classes through the `dispatchClasses` argument of `deep-codebase-audit`, and its `models` and `efforts` override one phase key each. A fix after a failed test or review (`run-planner reseat --cause review|test|carry-in|breach`) runs an implementer or test author at the base effort raised by `step` (1 plus the seat's earlier `review` or `test` cycles; a `carry-in` or `breach` cycle does not count), capped at `escalation.ceiling`; the output carries `step`, `effort`, `cause` and `ceilingReached`, the reseat ledger records `step`, `effort` and `cause` and the `extra-seats` provenance row records the effort and cause, and the session retrospective reports the failure loop's cost. `defaultModels` in the run profile and frontmatter `effort` in the agent definitions are gone. A guard test (`hooks/tests/contract-dispatch-literals.test.mjs`) fails on a model alias or effort level used as a dispatch value outside the policy file and its allow-list.

### run-wave on server runs

`/task-orchestrator:run-wave` resolves a frontier of ready items into a run plan (`scripts/run-planner.mjs`), then:

- builds the Step 2 snapshot with the helper subcommand `snapshot` over the REST API when a REST API URL resolves (by hand otherwise);
- opens the server run with `runs_write` `open` (answering an orchestrator token once), assigns one seat per schema-seat stage with `assign` (each answering a `seatToken` once), ends every seat with `end_seat` (with a post-run sweep) and closes the run with `close` (`completed`, `failed`, `cancelled` or `abandoned`);
- delivers each seat token through a token file, `<scratchpad>/run-wave/<runId>/tokens/<short>.<seat>.token`, never through prompt text, notes, logs or the plan; a seat sends its own token as the top-level `seatToken` of every call;
- reads run state from the server (`runs_read` `get`, `frontier`, `seats`), not from documents: the `run/<runId>` plan and state documents are no longer written;
- audits note presence after the seats return with the helper subcommand `notes-audit`, which lists a required stage-owned note with no observed row as `missing` and never an optional one; who wrote a note is not audited, because the server enforces each seat's lane (`note_owned_by_other`, `seat_forbidden`);
- executes the plan through the `implement-wave` Workflow (Method A) or direct `Agent` dispatch (Method B).
- in a Code Mode session (only `REPL` and `Poll` visible), runs each orchestrator step as a script from `skills/run-wave/references/code-mode.md`, so seat prompts, tokens and note bodies never enter the model's context; its prelude block is generated from `scripts/lib/code-mode-helpers.mjs`.

The plan id (`r-…`) names the scratch directory and the run's label; the server run id (a UUID) is bound into the plan with `bind-run`. Details: `skills/run-wave/SKILL.md` and `references/post-run.md`.

### Director mode and orchestrator placement

`/task-orchestrator:director` shows open runs, their frontier, open decisions and rollups through the director profile's connection and answers decisions (it withdraws one only as the decision's raiser, which includes a token-less raise by the same principal, or as the holder of the run's orchestrator token, which it has only from an `open` it made; nobody withdraws a server-raised decision); the guard hooks above hold it at altitude. Its purpose is to keep the user's session free and small while orchestrators run the waves. Two limits: one wave per project root at a time (`run-wave` resumes any open or paused run under the root instead of planning a new one), and the orchestrator placements (the tiers) differ in where the orchestrator lives: at tier 1 it is a separate session that outlives the director, at tier 2 it is a background agent inside the director session, at tier 2b it is a Code Mode subagent inside the director session that runs the whole run with Method B, at tier 3 it is a separate `claude -p` process and only the background agent that starts it lives inside the director session, and tier 4 has no orchestrator because the run executes inline in the director session. A director never runs a wave itself: an orchestrator does. `scripts/coordinator-tiers.mjs` (`selectTier`, `launchProfile`) picks where the orchestrator lives:

| Tier (placement) | Needs | Orchestrator |
|------|-------|-------------|
| 1 | `start_session` and `SendMessage` | A separate desktop session |
| 2 | `Workflow` | A background orchestrator agent; the director makes the one `Workflow` call with `args.altitudeSafe: true` |
| 2b | `REPL` and `Agent` (Code Mode) | A background Code Mode orchestrator subagent that runs the whole run with Method B |
| 3 | The `claude` CLI | A headless `claude -p --allowedTools Workflow --max-budget-usd <X>` process (never `--bare`) |
| 4 | Nothing above | None: the run executes inline, with an altitude warning |

Tiers 1, 2 and 2b need a director entered through the `/task-orchestrator:director` skill in a normal session. The hardened `claude --agent director` entry (`agents/director.md`) has neither `Workflow` nor `start_session`, so it probes tier 3 or 4.

An altitude-safe `implement-wave` result carries counts, statuses, commit SHAs, file lists and note keys, with reasons cut to 120 characters. Details: `skills/director/references/coordinator-tiers.md`.

### ralph on runs

`/task-orchestrator:ralph` emits the `scripts/ralph-loop.mjs` invocation. The loop needs the REST API (`TASK_ORCHESTRATOR_API_URL`) and a project root, and opens ONE run per loop (a cap only from `--max-attempts`, never a default one). Each iteration selects the oldest ready item with `items_read` `next`, adds it to the run, assigns the item's work seat, claims the item under that seat's token and spawns a fresh `claude -p --worktree`; the token reaches the iteration only as a `0o600` file path (`TASK_ORCHESTRATOR_SEAT_TOKEN_FILE`). The seat is ended on every outcome, and the run is closed on exit. The iteration signals its result with a `RALPH_OUTCOME:` marker. There is no `--actor` flag.

### The retrospective on run marks

A run labelled `<runId>-a<n>` (a fix-cycle run) always owes a retrospective in `dispatch` mode, whatever `minItems` says (at the `run` boundary).

At the default boundary `scope` a retrospective is owed per completed scope, covering every run of it, and the hooks fire it at that boundary (a low-signal scope is skipped by the hook at zero model tokens); at boundary `run` it is owed per closed run that has no `retro` mark. Either way it is owed to the session that opened or closed one of its runs (exceptions at the scope boundary, all limited to groups that became due after the session started: in a dispatch-configured project any session takes a completed scope 60 minutes after it finished, and a dead claim on such a group is taken over after 20 minutes; an abandoned scope belongs to its owner alone, nudged or dispatched only there). `runs_write` `close` triggers Retro Trigger, Retro Backstop checks at `Stop` via `runs_read` `unmarked: retro`, and the first `mark` wins. `/task-orchestrator:session-retrospective --run <runId>` takes the run's members from its ended seats and frontier. See [Self-Improving Workflow](self-improving-workflow.md).

---

## User Skills

Skills are invoked as slash commands in any Claude Code session:

| Command | Description |
|---------|-------------|
| `/task-orchestrator:work-summary` | Insight-driven dashboard of active work, blockers, open runs and recommended next actions |
| `/task-orchestrator:create-item` | Create a tracked work item with smart container anchoring and tag inference |
| `/task-orchestrator:quick-start` | Interactive onboarding — teaches by doing, adapts to empty or populated workspaces |
| `/task-orchestrator:init` | Set up a project root, config and the two MCP entries for this directory, or `--user` for a personal root that serves every unconfigured directory |
| `/task-orchestrator:manage-schemas` | Create, view, edit, delete, validate, and audit note schemas in config.yaml |
| `/task-orchestrator:status-progression` | Navigate role transitions; shows current gate status and the correct trigger |
| `/task-orchestrator:dependency-manager` | Visualize, create, and diagnose dependency graphs between work items |
| `/task-orchestrator:batch-complete` | Complete or cancel multiple items at once — close out features or workstreams |
| `/task-orchestrator:run-wave` | Plan and run a multi-item wave on a server run (Method A via the `implement-wave` Workflow, or Method B direct-Agent fallback) |
| `/task-orchestrator:ralph` | Emit the `ralph-loop.mjs` invocation for a headless queue drain — one run per loop, one `claude -p --worktree` per iteration |
| `/task-orchestrator:session-retrospective` | Analyze an implementation run (`--run <runId>`), record cross-session trends, and graduate recurring patterns into improvement proposals |
| `/task-orchestrator:review-retrospectives` | User-run review of the retrospective record: Summarise (dashboard), Suggest (ranked actions by product, global-process, project), apply confirmed trend changes, Decide (hand pending proposals to review-proposals); `--audit` and `--deep` add the retro-analysis workflow |
| `/task-orchestrator:review-proposals` | Triage pending improvement proposals — accept, reject, or defer each and carry out the disposition (user-invoked only) |
| `/task-orchestrator:adopt-project-scope` | Migrate an already-populated, unscoped database in place to the project-scoping convention |
| `/task-orchestrator:director` | Director mode: a board of open runs, frontier, decisions and rollups; answer decisions; delegate every read; `off` leaves |
| `/task-orchestrator:auditing-codebase` | One broad pass over existing code (source, tests and the comments in them): picks where to look by measuring churn, complexity and coupling, applies design signals, the slop catalog, an architecture pass and a glossary-drift check there, and ranks refactoring candidates Act now, Plan it or Watch; it reads a handed digest's candidate list and marks each claim measured or judgement. Document files go to `/task-orchestrator:auditing-documentation`. Files no items |
| `/task-orchestrator:run-audit` | Front door for the `deep-codebase-audit` workflow: builds its args (scope, preset, `dispatchClasses`, backlog), measures first (`audit-metrics.mjs --digest-out`), shows the projected agent count and dispatch classes for confirmation, passes the digest as `measured`, launches it, saves the result JSON beside the report after `audit-confirm.mjs` has checked its measured claims against the metrics file, renders the HTML report (with a Since last audit comparison when an earlier confirmed result of the same scope sits there, `--previous`) and offers the findings as items through `create-item` (main session only) |
| `/task-orchestrator:auditing-documentation` | One broad pass over existing document files (READMEs, guides, user docs, agent instruction files): judges each against its audience, finds what restates the code, drifts from it or is missing, and ranks candidates Act now, Plan it or Watch; records are exempt from drift findings. Edits no file and files no items |
| `/task-orchestrator:configure-server` | Configure how the server runs and is reached — transport, REST API, port publishing, config mounts, config-sync |

---

## Note Schema Integration

The plugin works best when combined with note schemas (Tier 3). The pre-plan hook reads your `config.yaml` schemas to inform the definition floor. The subagent-start protocol uses `guidanceKey` (resolved via `items_read` `select: "schema"`) from those schemas to tell agents exactly what to write.

See [Note Schemas](note-schemas.md) for schema setup.

---

## When to Level Up

**Signal:** You want Claude to operate as a full workflow orchestrator — planning, delegating, tracking, and reporting — rather than implementing directly.

**Next:** [Orchestration Mode](orchestration-mode.md) — the tier-aware, delegation-based operation the plugin's orchestration context delivers.
