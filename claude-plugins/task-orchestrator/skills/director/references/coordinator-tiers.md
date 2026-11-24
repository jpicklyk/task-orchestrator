# Orchestrator placements and launch profiles

A director session never runs a wave itself. An orchestrator does: it plans the run, makes or
receives the run's seat calls, and runs post-run, then reports a short summary. The tier says where
that orchestrator lives. `scripts/coordinator-tiers.mjs` holds the rule and the profiles:

- `selectTier({tools, claudeCli})` returns `{tier, warning}`. A tool name matches a requirement when
  it equals it or ends with `__` plus it (so `mcp__ccd_session__start_session` matches
  `start_session`, and `start_session_v2` does not). A bare REPL and Poll list (every entry matches
  `REPL` or `Poll`, at least one `REPL`, tier 1 and 2 not met) returns
  `{tier: null, reprobe: true, warning}`: it is the visible Code Mode pair, not the real list, so
  list the tools a script can call and probe again. A list holding `REPL` and `Agent` (Code Mode,
  Workflow not present) selects tier `2b`, a string id: tiers 1, 2, 3 and 4 stay numbers.
- `launchProfile(tier, opts)` returns the launch profile below.
- `node scripts/coordinator-tiers.mjs profile --tier N|2b [--budget-usd X] [--scope S] [--target T] [--model M] [--effort E] [--prompt-file F]`
  prints it as JSON (`--tier 2b` needs `--scope`; `--target` defaults to the first 8 hex of the scope id). An orchestrator or background agent runs this, never the director session (the
  guard denies `node` there).

The director probes its own tool list with the same rule, once per entry, without a call.
In Code Mode the probe reads the tools a script can call (Workflow, Agent, SendMessage and the
rest), not the visible REPL and Poll pair.

| Tier | Needs | Orchestrator | Launch profile |
|------|-------|-------------|----------------|
| 1 | `start_session` and `SendMessage` (or `send_message`) | A separate desktop session | `start_session` with `initiation: "user_asked"`, `context: "fresh"`, `model`, `effort`, `permission_mode`; output style set after start; `crossSessionInbound: "accept"`; talk with `SendMessage` |
| 2 | `Workflow` | A background orchestrator agent; the director makes the one `Workflow` call | `task-orchestrator:implement-wave` with `args.altitudeSafe: true` |
| 2b | `REPL` and `Agent` (Code Mode) | A background Code Mode orchestrator subagent that runs the whole run, Method B; seat results stop at it | one `Agent` call: model/effort from the `coordinator` class, description `coordinator:<target-short>`, the prompt is line 1 of the 2b brief alone (the full brief comes from `launchProfile('2b', {scope})`); no `run_in_background` |
| 3 | The `claude` CLI on the host | A headless `claude -p` process, or `node scripts/ralph-loop.mjs` (a queue drain), started by a background agent | `claude -p <prompt> --allowedTools Workflow --max-budget-usd <X> --model <M> --effort <E>`, never `--bare` |
| 4 | Nothing above | None: the run executes inline | `inline: true` with the altitude warning |

Tiers 1, 2 and 2b need director mode entered through the `/task-orchestrator:director` skill in a normal
session. The hardened `claude --agent director` entry (`agents/director.md`) has neither `Workflow`
nor `start_session`, and its tool list has no `REPL`, so it probes tier 3 or 4, never 2b.

## Tier 1: desktop session

`start_session` takes `model`, `effort` and `permission_mode`. For tiers 1, 2b and 3, model and effort
default to the dispatch policy's `coordinator` class (see `task-orchestrator:model-effort`); a given
value overrides each. Set the output style after the
session starts, with `set_session_output_style`.

No probed `start_session` parameter carries `crossSessionInbound`. Before launching, ask the user to
set cross-session inbound messages to accept in the desktop settings, and check it with
`get_settings`. If it is not `accept`, warn the user: under bypass, held messages are dropped after
five minutes, so an orchestrator's report can be lost.

The desktop session tools are undocumented and may change. Re-probe the tool list on every entry
rather than relying on a remembered shape.

## Tier 2: one Workflow call, everything else in an orchestrator

A director session cannot run run-wave itself: the guard denies the `node` helper, and the
snapshot reads note bodies. So the work splits into five steps:

1. The director dispatches one named background orchestrator agent (`run_in_background: true`,
   `model` set, Agent `description` `coordinator:<target-short>`, see the post-run.md Label rule) for run-wave Steps 0-5a with `--altitude-safe --method A`. The orchestrator records
   run phase `planned`, writes its run token to `tokens/coordinator.token` (the `tokenFile`
   `run-ops --phase open` names), and returns the `planned` report plus `{runId, serverRunId, args}`.
2. When that report says `needs: approve launch` (excluded items, degradations or missing allow rules), the
   director gets the user's approval first. When the line also reads `allow rules missing: <n> (<names>)`
   (the probe's `allow` members that are false: `workflow`, `mcp`, `bashGit`, `bashNode`; `workflow` is not named under Method B), the
   director shows the user those names with three answers: the user adds the rules (nobody edits
   settings for them), launch anyway and accept the permission prompts, or stop.
3. The director calls `Workflow` with the `args`, then sends the orchestrator a `SendMessage`
   `launched` carrying the Workflow call's taskId, run id and script path. Only then does the
   orchestrator record phase `launched` (and those keys) in `state.json`. `args.altitudeSafe: true`
   makes implement-wave return the altitude-safe result: `rollup` counts, item and stage statuses,
   commit SHAs, file lists, note keys and seat-raised `decisionId`s, with every reason cut to 120
   characters and `redProofShape` cut to 200, the planner `sweep` entries (kind, hits, disposed, the unowned file names, and the grep cut to 120
   characters), the `testPlanScenarios` and `manifestScenarios` id lists, and no other seat prose.
4. On the task notification, the director sends the orchestrator the result JSON verbatim. When the
   notification is cut short, the director sends the output file path the notification names
   instead; it never reads that file. The orchestrator writes `result.json` (recovering `result` from
   that file per post-run Step 0) and runs post-run (Step 0 saves and verifies the safe result the same way as a full
   one; the prose is in the seats' notes).
5. The orchestrator reports per "Orchestrator reports" below.

There is one implement-wave call per run. A queue/work split inside one run is not possible: work
seats consume planner output in-process. Decisions land between runs.

## Tier 2b: Code Mode orchestrator subagent

A director-mode session in Code Mode has `Agent` but may have no `Workflow`, and its orchestrator subagent has none either, so
the orchestrator runs the whole run itself with Method B (run-wave `references/code-mode.md`); seat
results reach the orchestrator, not the director. When both 2 and 2b are available, pick 2 for a
Method A run and 2b when Method B suits: the plan exits 3 on `--method A` because `maxActiveSeats` is
below the schema-seat count, or the director wants no `Workflow` call in its context.

The director cannot run `node` and the guard denies it a `Read` of this file, so it composes the one
`Agent` call from line 1 of the 2b brief alone (model and effort from the `coordinator` class,
`subagent_type` `general-purpose`, description `coordinator:<target-short>`, prompt the line `Run
/task-orchestrator:run-wave <scope> --method B as a tier-2b orchestrator.`): the orchestrator's run-wave
skill tells it to read this brief first and follow it. `launchProfile('2b', {scope})` returns the same call
with the full brief for an orchestrator or background agent that needs it. There is no `launched` hand-off:
the orchestrator records `launched` itself, on its first dispatch round. The Code Mode `Agent` call is always
background and takes no `run_in_background`.

**Launch gate.** The orchestrator records phase `planned` and sends the `planned` report. When that report's
`needs:` line is not `nothing` (excluded items, degradations or missing allow rules), the orchestrator makes no
seat call until the director's message: the director gets the user's approval (the same three answers as
tier 2 step 2: the user adds the rules, accept the permission prompts, or stop), then `SendMessage`s the
orchestrator `approved` or `stop`. `approved` launches; `stop` closes the run `cancelled` and reports
`closed`. With `needs: nothing` the orchestrator launches at once.

### Tier 2b brief

```
Run /task-orchestrator:run-wave <scope> --method B as a tier-2b orchestrator.
You are a tier-2b orchestrator: a Code Mode subagent. Run every step that skills/run-wave/references/code-mode.md covers through its recipe; you have no Workflow tool.
Report to "main" with SendMessage, only the output of the report helper (events planned, decision, paused, closed). Never send note bodies, diffs or tokens.
Run each round as a short script, always well under the ~600 s REPL script limit (it binds a subagent whatever its $ timeout), and never wait inside a script.
Between rounds, wait on Poll with no script running: an idle orchestrator is woken by its seats' task notifications.
Take each seat's envelope from its task-notification result text, never from the task .output file, which stays empty.
Never use a standalone sleep: a shell guard blocks it.
After decision or paused, wait for the director's message; never answer a decision yourself. Stop on a director close and report closed.
After a planned report whose needs: line is not nothing, wait for the director's message before the first dispatch round: approved launches, stop closes the run cancelled and reports closed.
Never ask the user anything: put what you need (a decision, a permission) on the report's needs: line.
At post-run Step 3 pass --coordinator coordinator:<target-short> to provenance (exact label only).
```

## Tier 3: headless claude -p

The argv is `claude -p <prompt> --allowedTools Workflow --max-budget-usd <X> --model <M> --effort <E>`. `launchProfile(3)` throws unless `budgetUsd` is a finite number greater than 0, then
throws unless `prompt` is a non-empty string (the CLI exits non-zero without `--prompt-file`).

- The background agent that starts the process plans first (run-wave Steps 0-5a with
  `--altitude-safe`), passes the plan's `args` in the prompt, and runs post-run after the process
  exits; the process makes only the `Workflow` call.
- `--max-budget-usd` is the only configured hard stop: plans open runs with no budget, and the
  server's run limits return early without one. The account usage limit is not a configured stop.

- Never `--bare`: a bare session skips the hooks installed plugins define, and it reads auth only from `ANTHROPIC_API_KEY` or
  `apiKeyHelper`.
- A headless process fails rather than pauses on a usage limit. `--max-budget-usd` bounds the
  spend; a failed run is resumed from the server's run state, not from the process.
- Do not set `TASK_ORCHESTRATOR_MODE`. `headless-iteration` would silence the plugin's
  subagent-start hook for Method B seats, which then lose their phase protocol.

## Orchestrator reports

An orchestrator tells the director four things and nothing else. The director otherwise learns of a
decision only from a manual refresh.

- **When.** `planned` (end of run-wave Step 5a; the tier-2 orchestrator's return), `decision` (a seat
  stopped `awaiting_decision`, or the orchestrator raised one), `paused` (`run_paused`, or a
  stalled seat whose re-assign hit the attempt cap), `closed`
  (any close: completed, failed, cancelled, abandoned, or closed elsewhere).
- **What.** Only the output of `node scripts/run-planner.mjs report --plan <plan.json> --event
  <planned|decision|paused|closed> [--result F] [--run F] [--close F] [--paused F] [--needs TEXT]`, or ralph's own
  block. It is a fixed plain-text block (`TO report: run <label> (<run8>) <event>: <headline>`, then
  `run:`, `decisions:`, `items:`, `stopped:`, `close:` and `needs:` lines) of ids cut to 8 hex,
  counts and reasons cut to 120 characters. Never hand-write it, and never send note text, diffs,
  seat prose or a token.
- **To whom.** Tier 1: `SendMessage` to the director session name the brief gives (reply with the
  `from` of its message). A background orchestrator or relay agent (tiers 2, 2b, 3 and 4): `SendMessage` with
  `to: "main"` while it runs, and its final return for the event that ends its turn. With no
  director (a user session runs run-wave), show the block to the user.
- **After `decision` or `paused`** the orchestrator waits for the director's message (answered,
  resume, close). It never answers a decision itself. A tier-2b orchestrator also waits after a `planned`
  report whose `needs:` line is not `nothing`, for `approved` or `stop` (see the launch gate above).

### Orchestrator brief

The `prompt` of `start_session` or the background `Agent`, verbatim at tiers 1, 2 and 4 (tier 2b has its own brief above). At tier 3 the
brief goes to the background agent that starts the process, not to the process: the process prompt
carries only the plan's args.

```
Run /task-orchestrator:run-wave <scope> --altitude-safe   (tier 2 also: --method A).
Report to <director address> with SendMessage, only the output of the report helper
(events planned, decision, paused, closed). Never send note bodies, diffs or tokens.
Budget: <X>. Stop on a director close and report closed.
Never ask the user anything: put what you need (a decision, a permission) on the report's `needs:` line.
At post-run Step 3 pass --coordinator coordinator:<target-short> to provenance (tiers 2 and 2b; exact label only).
```

### Ralph as a tier-3 orchestrator

A background agent starts `node scripts/ralph-loop.mjs` with Bash `run_in_background: true` and
relays each `TO report:` block it prints per the rules above (Monitor on its output for a mid-run
pause; otherwise on exit). Ralph prints a `paused` block when it starts waiting on a paused run and a
`closed` block after its final summary. Ralph cannot SendMessage, never raises or answers a decision,
and never resumes the run; the relaying agent never closes ralph's run.

### Orchestrator health on the board

One stall threshold covers seats and the orchestrator, and only a token-carrying call touches
liveness, so during a Method A Workflow the orchestrator reads as stalled although nothing is wrong.
The board therefore shows `coord idle (Method A)` when the run hints say method A and phase
`launched`, `coord idle, seats live` when any in-flight seat is not stalled, nothing on a paused run,
and otherwise `COORD STALLED`: SendMessage the orchestrator; if it is gone, close the run and
re-plan.

## Limits

- One wave per project root at a time: run-wave resumes an open or paused run instead of starting a second.
- The tier-2 and tier-2b orchestrators, the agent that starts a tier-3 process, and tier 4 live inside the director
  session and end with it. A tier-1 session does not.
- Nothing wakes the director but an orchestrator report, a task notification or the user.
- A tier-2 Workflow resume is not the director's call: the guard denies `Workflow` with a `scriptPath` or
  `resumeFromRunId`, so it falls to the orchestrator, a background agent that may lack `Workflow`
  (run-wave post-run re-probes it for that reason). A resume may then have no one able to make it.
- The director cannot plan: it reads no files or notes. New work is planned in orchestration mode before
  entering director mode.

## Tier 4: inline, with a warning

With no orchestrator placement available, the run executes in the director session. Warn the user once:
this breaks the altitude rule, because the director then reads and dispatches seats itself. Keep
the run small, or make the `Workflow` tool or the `claude` CLI available. A small run (one or two
items) needs no separate orchestrator at any tier: dispatch it as one background agent.
