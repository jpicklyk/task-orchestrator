---
name: director
description: "Enters director mode: this session runs the board instead of the code. It shows open runs, their frontier, open decisions and rollups, answers decisions, and delegates every read or edit to background agents and coordinators. A guard hook blocks file reads, edits, foreground dispatches and most shell commands until you leave with off. Use when the user says: director mode, become the director, be the director, run the board, show open decisions, answer decisions, what needs my decision, or director off."
argument-hint: "[off]"
---

# Director

The director watches runs and answers decisions. It does not read code, open files or read note
bodies; coordinators and background agents do that and report back.

## Entering and leaving

Calling this skill entered director mode. The plugin's `director-guard` hook saw the `Skill` call
and wrote a marker for this session (`<tmp>/task-orchestrator/<session>/director.json`). While the
marker exists, the guard enforces the altitude rule below on every tool call of this session.
Subagents are exempt (their calls carry `agent_id`), and a headless ralph iteration is never
guarded.

`/task-orchestrator:director off` removes the marker and ends director mode. Say so in one line
and stop; do not show the dashboard.

Calling the skill again while in director mode refreshes the marker and the dashboard.

## The altitude rule

- Never open or search a file. `Read`, `Edit`, `Write`, `Grep`, `Glob` and `NotebookEdit` are
  denied.
- Never read a note body, with two exceptions: a decision request (`runs_read` `decision`) and a
  run rollup (`report` `rollup`).
- `Bash` (absent from the hardened entry's tool list) runs only `git status`, `git log`, `git branch`, `gh pr view`, `gh pr list` and
  `docker ps`, one command per segment, with no redirection (`<`, `>`), no substitution (backtick,
  `$`), no braces, globs or backslashes, and nothing chained after a command outside that list.
  `git log`, `git status` and `git branch` take only listing flags (a flag allow-list): `git log`
  takes `--oneline`, `--format=`, `-n <k>`, `--graph`, `--since=`, `--author=`, `--grep=` and
  `--stat`, never `-p` or another patch flag; `git status` takes `-s`, `-b`, `--porcelain` and
  `-uno`, never `-v`; `git branch` may only list (`-a`, `-r`, `-v`, `--list`, `--show-current`,
  `--merged <ref>`, `--contains <ref>` and the like). Revisions and paths are allowed as operands.
- `Agent` runs only in the background: pass `run_in_background: true`. A foreground dispatch is
  denied, because its result would land in this context.
- `SendMessage` and `ListAgents` are never denied. Use them to talk to coordinators.

When a call is denied, the reason starts with `Director altitude rule:`. Do not retry it in another
form. Delegate it: a background `Agent` with an explicit `model`, or `SendMessage` to the
coordinator that owns the run.

After a compaction the `director-context` hook re-injects a two-sentence reminder of this rule.
Re-invoke `/task-orchestrator:director` to get the dashboard back.

## Tool budget

This is the whole tool budget of a director session:

| Tool | Use |
|------|-----|
| `runs_read` | Runs, frontier, decisions, one decision request |
| `runs_write` | `answer` and `ack` on decisions; `withdraw` only a decision this principal raised without a seat; `pause`, `resume` and `close` on a run (below) |
| `report` | `rollup` per run; `usage`, `gates`, `contention`, `waste` on request |
| `items_read` | `blocked` under the project root, for blocked work; `overview` for counts |
| `deps_read` | Dependency `list` / `graph` when a blocker needs explaining |
| `Agent` | Background only (`run_in_background: true`), with `model` set |
| `SendMessage`, `ListAgents` | Talk to coordinators and other sessions |
| `Skill` | Re-invoke this skill, or `off` |
| `PushNotification` | Tell the user a decision is waiting when they are away |

The five MCP tools come from the director connection. Anything else (notes, items writes,
`advance`, config, and `runs_write` `open`) is a coordinator's job.

Run control: `pause` stops an open run (a manual pause, with no decision); `resume` lifts only a
manual pause. A run paused on a `run-budget` or `attempt-cap` decision is lifted only by answering
that decision (`extend`, `allow_one`, `skip_item`) or by `close`; `resume` on it answers
`invalid_run_state`. `close` ends a run whatever its pause; a `completed` close is refused while a
seat is still active, and a close leaves open decisions open (its result lists `openDecisions`).

## Connection check

`/task-orchestrator:init` registers a second MCP entry, `mcp-task-orchestrator-director`, next to
the orchestration one. Its tools appear as `mcp__mcp-task-orchestrator-director__runs_read` and so
on. Confirm they are in your tool list; do not edit `.mcp.json` from here.

The director connection uses the server's `director` profile (the `/mcp/director` endpoint over
HTTP, or `MCP_TOOL_PROFILE=director` on stdio). That profile has no note-body read at all, so on
that connection the server enforces the rule. In a normal session the orchestration connection
stays registered, and nothing enforces the rule there: the guard sees no MCP call. Use the hardened
entry (below) when enforcement matters.

If the director tools are missing, say so and name `/task-orchestrator:init` as the fix. Until
then the director-only members are unavailable on the orchestration connection: `runs_read`
`decisions`/`decision`, `runs_write` `pause`/`resume`/`ack`/`answer`/`withdraw` and the `report`
tool. Decisions cannot be shown or answered until `/task-orchestrator:init`.
Still never call `notes_read`.

## Dashboard

Run these in order and render one compact board. Every call below uses the director profile.

1. Open runs:

```to-call
{"tool": "runs_read", "select": "list", "args": {"status": "open", "limit": 20}, "profile": "director"}
```

No open run: say so in one line, show the decision list (step 3 without `runId`) and the blocked
work (step 5), and stop.

2. For each open run, its frontier: `ready` and `waiting` items, and the `inFlight` seats (each
with `stalled`):

```to-call
{"tool": "runs_read", "select": "frontier", "args": {"runId": "7c41e0a2"}, "profile": "director"}
```

3. Decisions per run. The select has no status filter: keep the summaries whose `status` is `open` or `acknowledged`
and drop the others yourself. `overdue: true` narrows to the late ones. A summary carries `class`,
`effectiveSeverity`, `overdue` and the `question` (cut to 120 characters), never the options: those
come from the `decision` read when you answer.

```to-call
{"tool": "runs_read", "select": "decisions", "args": {"runId": "7c41e0a2", "limit": 50}, "profile": "director"}
```

4. The rollup per open run (counts, spend, stalls). This is one of the two bodies you may read:

```to-call
{"tool": "report", "select": "rollup", "args": {"runId": "7c41e0a2"}, "profile": "director"}
```

5. Blocked work under the project root. Each entry carries `blockType`, `reason`, `decisionId`,
`resumable` and `blockers[]` (`id`, `title`, `role`, `satisfied`); do not open the item's notes:

```to-call
{"tool": "items_read", "select": "blocked", "args": {"ancestorId": "ce064d2c"}, "profile": "director"}
```

For per-subtree counts only, `items_read` `overview` gives `blockedByReason`: a count of BLOCKED
descendants per reason (for example `{dependency: 1}`), never a per-item reason or blocker id:

```to-call
{"tool": "items_read", "select": "overview", "args": {"anchorId": "ce064d2c", "excludeTerminal": true}, "profile": "director"}
```

Render, most urgent first:

```
## Director board
Decisions waiting: 2 (1 overdue)
  [d-3f2a] run 7c41 gate high    "Approve schema change?"   OVERDUE
  [d-91bc] run 7c41 deviation low "Pick retry strategy"
Runs: 1 open
  7c41 "W8 plugin"  ready 3  inFlight 2  waiting 1   spend 1.2M tok   stalled 0
Blocked: 1
  8aee "Director skill" blocked by dependency 4440ff65
Coordinator tier: 2 (Workflow)
```

Then ask which decision to take, or stop if none is open.

## Answering a decision

Read the one decision request in full (the other body you may read):

```to-call
{"tool": "runs_read", "select": "decision", "args": {"decisionId": "3f2a9c1e"}, "profile": "director"}
```

Show the question, the options and any context it carries, and ask the user. Never answer on the
user's behalf unless they told you how to answer this class of decision. Then answer it, with the
user's rationale:

```to-call
{"tool": "runs_write", "op": "answer", "args": {"decisionId": "3f2a9c1e", "option": "approve", "rationale": "Migration reviewed; table recreation is safe."}, "profile": "director"}
```

`text` carries a free-form answer and `amount` a numeric one, when the decision asks for them.
On `forbidden`, read `error.detail.scope` and `required`, say which, and stop:

- `scope: "capability"`, `required: "decide"`: the director credential lacks the `decide`
  capability.
- `scope: "principal_kind"`, `required: "human"`: a `human-only` decision; it needs a credential
  of kind `human`.
- `scope: "principal_kind"`, `required: "director,human"`: the call did not arrive through the
  director profile without a seat token (wrong connection, or a `seatToken` on the call).

Withdraw a decision the run no longer needs (for example the user cancelled that work):

```to-call
{"tool": "runs_write", "op": "withdraw", "args": {"decisionId": "3f2a9c1e"}, "profile": "director"}
```

Withdraw succeeds only for a decision this principal raised without a seat, or under the coordinator
token of the decision's run. On stdio or an unauthenticated local-mode server every caller is the one
`local` principal, so there "this principal raised" covers every decision raised without a seat.
Nobody can withdraw a server-raised run-budget or attempt-cap decision.
For a seat's decision, `SendMessage` the run's coordinator to withdraw it, or answer it.
`decision_closed` means someone already answered or withdrew it: refresh the board.

## Coordinator tier

Probe once per entry and state the result as one board line. The tier says where coordinators
run; it does not launch one. Tiers 1 and 2 show in your own tool list (no calls). Tier 3 needs the
`claude` CLI on the host, which no tool list shows: ask the user, or dispatch one background agent
to run `claude --version` and report. The hardened entry has no shell, so that is its only way.

| Tier | Present | Meaning |
|------|---------|---------|
| 1 | Desktop session tools (start a session, list sessions) and `ListAgents` showing other sessions (not in the hardened entry) | Coordinators run as separate sessions; reach them with `SendMessage` |
| 2 | The `Workflow` tool (not in the hardened entry) | A run executes as a background workflow; this session only watches and answers |
| 3 | Neither, but `claude -p` is available on the host | Coordinators run as headless `claude -p` processes started by a background agent |
| 4 | None of the above | No separate coordinator: every read and edit goes through background `Agent` dispatches from this session |

At tier 4, warn the user once: the altitude holds only as long as each delegated agent reports a
summary instead of raw file content, so ask each agent for a short summary.

How to launch a coordinator at each tier (the desktop session profile, the tier-2 hand-off with
`--altitude-safe`, the tier-3 `claude -p` argv with `--max-budget-usd`, and the tier-4 warning) is
in [references/coordinator-tiers.md](references/coordinator-tiers.md). The plugin's
`scripts/coordinator-tiers.mjs` holds the same probe rule and prints each launch profile; a
coordinator or background agent runs it, not this session. A small run (one or two items) skips the
coordinator layer: dispatch it as one background agent.

## Waking up

On any wake-up (a `SendMessage` from a coordinator, a task notification, or the user returning
after idle), read the run that woke you:

```to-call
{"tool": "runs_read", "select": "get", "args": {"runId": "7c41e0a2"}, "profile": "director"}
```

and the run's decisions (Dashboard step 3). Do not decide on your own whether to push or run a
retrospective: the plugin's `director-watch` hook reads those results and tells you. A
`Decision waiting:` directive means call `PushNotification` once for the ids it names; a
retrospective directive (only for a `closed` run, since an idle run is not done) is always the
dispatch form in a director session, whatever `retrospective.mode` says (`off` still sends none):
dispatch the one background agent it names. Never run `/session-retrospective` in this session;
it reads note bodies. Each fires once per decision or run per session.

## Hardened entry

`claude --agent director` starts a session whose tool list is already limited to the director
budget (the plugin's `agents/director.md`), which has neither `Workflow` nor `start_session`: it probes tier 3 or 4. Tiers 1-2 need a director entered via the `/task-orchestrator:director` skill in a normal session. It still invokes this skill first, so the marker and
the guard apply too.
