# Coordinator tiers and launch profiles

A director session never runs a wave itself. A coordinator does: it plans the run, makes or
receives the run's seat calls, and runs post-run, then reports a short summary. The tier says where
that coordinator lives. `scripts/coordinator-tiers.mjs` holds the rule and the profiles:

- `selectTier({tools, claudeCli})` returns `{tier, warning}`. A tool name matches a requirement when
  it equals it or ends with `__` plus it (so `mcp__ccd_session__start_session` matches
  `start_session`, and `start_session_v2` does not).
- `launchProfile(tier, opts)` returns the launch profile below.
- `node scripts/coordinator-tiers.mjs profile --tier N [--budget-usd X] [--model M] [--prompt-file F]`
  prints it as JSON. A coordinator or background agent runs this, never the director session (the
  guard denies `node` there).

The director probes its own tool list with the same rule, once per entry, without a call.

| Tier | Needs | Coordinator | Launch profile |
|------|-------|-------------|----------------|
| 1 | `start_session` and `SendMessage` (or `send_message`) | A separate desktop session | `start_session` with `initiation: "user_asked"`, `context: "fresh"`, `model`, `effort`, `permission_mode`; output style set after start; `crossSessionInbound: "accept"`; talk with `SendMessage` |
| 2 | `Workflow` | A background coordinator agent; the director makes the one `Workflow` call | `task-orchestrator:implement-wave` with `args.altitudeSafe: true` |
| 3 | The `claude` CLI on the host | A headless `claude -p` process started by a background agent | `claude -p <prompt> --allowedTools Workflow --max-budget-usd <X> [--model <M>]`, never `--bare` |
| 4 | Nothing above | None: the run executes inline | `inline: true` with the altitude warning |

Tiers 1 and 2 need a director entered through the `/task-orchestrator:director` skill in a normal
session. The hardened `claude --agent director` entry (`agents/director.md`) has neither `Workflow`
nor `start_session`, so it probes tier 3 or 4.

## Tier 1: desktop session

`start_session` takes `model`, `effort` and `permission_mode`. Set the output style after the
session starts, with `set_session_output_style`.

No probed `start_session` parameter carries `crossSessionInbound`. Before launching, ask the user to
set cross-session inbound messages to accept in the desktop settings, and check it with
`get_settings`. If it is not `accept`, warn the user: under bypass, held messages are dropped after
five minutes, so a coordinator's report can be lost.

The desktop session tools are undocumented and may change. Re-probe the tool list on every entry
rather than relying on a remembered shape.

## Tier 2: one Workflow call, everything else in a coordinator

A director session cannot run run-wave itself: the guard denies the `node` helper, and the
snapshot reads note bodies. So the work splits:

1. The director dispatches one named background coordinator agent (`run_in_background: true`,
   `model` set) for run-wave Steps 0-5a, with `--altitude-safe` on the planner's `plan` call. It
   keeps the coordinator token and returns only `{runId, serverRunId, args}`.
2. The director calls `Workflow` with those `args`. `args.altitudeSafe: true` makes implement-wave
   return the altitude-safe result: `rollup` counts, item and stage statuses, commit SHAs, file
   lists and note keys, with every reason cut to 120 characters `redProofShape` cut to 200, and no other seat prose.
3. On the task notification, the director sends that coordinator a `SendMessage` to run post-run
   (Step 0 saves and verifies the safe result the same way as a full one; the prose is in the seats'
   notes).

There is one implement-wave call per run. A queue/work split inside one run is not possible: work
seats consume planner output in-process. Decisions land between runs.

## Tier 3: headless claude -p

The argv is `claude -p <prompt> --allowedTools Workflow --max-budget-usd <X>`, plus `--model <M>`
when given. `launchProfile(3)` throws unless `budgetUsd` is a finite number greater than 0, then
throws unless `prompt` is a non-empty string (the CLI exits non-zero without `--prompt-file`).

- The background agent that starts the process plans first (run-wave Steps 0-5a with
  `--altitude-safe`), passes the plan's `args` in the prompt, and runs post-run after the process
  exits; the process makes only the `Workflow` call.
- `--max-budget-usd` is the only configured hard stop: plans open runs with no budget, and the
  server's run limits return early without one. The account usage limit is not a configured stop.

- Never `--bare`: a bare session cannot receive messages, so the director could not reach it.
- A headless process fails rather than pauses on a usage limit. `--max-budget-usd` bounds the
  spend; a failed run is resumed from the server's run state, not from the process.
- Do not set `TASK_ORCHESTRATOR_MODE`. `headless-iteration` would silence the plugin's
  subagent-start hook for Method B seats, which then lose their phase protocol.

## Tier 4: inline, with a warning

With no coordinator tier available, the run executes in the director session. Warn the user once:
this breaks the altitude rule, because the director then reads and dispatches seats itself. Keep
the run small, or make the `Workflow` tool or the `claude` CLI available. A small run (one or two
items) skips the coordinator layer at any tier: dispatch it as one background agent.
