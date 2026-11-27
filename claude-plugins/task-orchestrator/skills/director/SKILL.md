---
name: director
description: "Enters director mode: this session acts on your board at your instruction, not on the code. It shows open runs, their frontier, open decisions and rollups, answers decisions, and delegates every read or edit to background agents and orchestrators. A guard hook blocks file reads, edits, foreground dispatches and most shell commands until you leave with off. Use when the user says: director mode, become the director, be the director, run the board, show open decisions, answer decisions, what needs my decision, or director off."
argument-hint: "[off]"
---

# Director

In director mode this session is the director: it watches runs and answers decisions on the
user's board (their view in either mode), at their instruction. It does not read code, open files or read note
bodies; orchestrators and background agents do that and report. Why the layer exists, and when
not to use it: [references/purpose.md](references/purpose.md).

## Entering and leaving

Calling this skill entered director mode. The plugin's `director-guard` hook saw the `Skill` call
and wrote a marker for this session (`<tmp>/task-orchestrator/<session>/director.json`). While the
marker exists, the guard enforces the altitude rule below on the tools it matches: the built-in file, shell, web (`WebFetch`), MCP-resource and content tools, foreground dispatch, `Workflow`, the reads and writes on the orchestration connection, and every host MCP tool (terminal, file store, computer use), which is denied unless it is `start_session`, `send_message`, `archive_session` (ending a finished orchestrator's session), a tier-1 launch profile call (`get_settings`, `set_session_output_style`) or a desktop UI tool (`update_plan`, `wrap_up`, `mark_chapter`).
Subagents are exempt (their calls carry `agent_id`); a headless ralph iteration is never guarded.

`/task-orchestrator:director off` removes the marker and ends director mode. Say so in one line
and stop, without the dashboard.

Calling the skill again refreshes the marker and the dashboard.

## The altitude rule

- Never open or search a work file. `Read` may open a skill's files (its `SKILL.md`, references and
  scripts, this skill's included) and any file inside an installed plugin (not a plugin's source in the repository
  you work in: that is work content). `Edit`, `Write`, `Grep`, `Glob` and `NotebookEdit` are denied. `PowerShell` and `Monitor` are denied outright (each runs a shell command and prints its output, and only `Bash` has an allow-list); `Workflow` runs only `task-orchestrator:implement-wave` with an object `args` carrying
  `altitudeSafe: true`.
- Never read a note body, a diff or test output, with two exceptions: a decision request (`decisions_read` `list` with `decisionIds`) and a
  run rollup (`report` `rollup`). A decision carries its evidence in `context`; if not, a background
  agent reads the notes and returns ids, a verdict and one line.
- One board read per wake; act on its ids and do not re-read.
- Never drive work. `advance`, `items_write`, `notes_write`, `deps_write` and `docs_write` are denied;
  `decisions_write` runs only `ack`, `answer`, `delegate`, `escalate` and `withdraw`; `runs_write` runs only `answer`, `ack`, `withdraw`, `pause`, `resume`, `close` and `reissue`.
- `Skill` is open: each call a loaded skill makes is checked on its own.
- `Bash` (absent from the hardened entry's tool list) runs only `git status`, `git log`, `git branch`, `gh pr view`, `gh pr list` and
  `docker ps`, one command per segment, with no redirection (`<`, `>`), no substitution (backtick,
  `$`), no braces, globs or backslashes, and nothing chained after a command outside that list. A
  segment may also be `node <script>` for a script inside an installed plugin (the same rule as `Read`), by its absolute path written
  with `/` (a path with a space does not run): those scripts give you deterministic routing and decisions,
  such as `scripts/coordinator-tiers.mjs`. The guard does not check what a script does, and some write files
  or drive work: run one only to route or decide, never a generator (`gen-*`), `ralph-loop` or a `--out` write.
  `git log`, `git status` and `git branch` take only listing flags (a flag allow-list): `git log` never `-p` or another
  patch flag, `git status` never `-v`, and `git branch` may only list. Revisions and paths are allowed
  as operands; full flag lists: [references/guard-coverage.md](references/guard-coverage.md).
- `Agent` runs only in the background: `run_in_background: true`. A foreground dispatch is denied,
  as its result would land here. In Code Mode (after a `REPL` call) and the desktop app, `Agent` has
  no such field and always runs in the background, so the guard allows it.
- `SendMessage` and `ListAgents` are never denied. Use them to talk to orchestrators.

When a call is denied, the reason starts with `Director altitude rule:`. Do not retry it in another form. Delegate it: a background `Agent` with an explicit `model`, or `SendMessage` to the
orchestrator that owns the run.

After a compaction the `director-context` hook re-injects a short reminder of this rule.
Re-invoke `/task-orchestrator:director` for the dashboard.

## Tool budget

The whole tool budget of a director session:

| Tool | Use |
|------|-----|
| `runs_read` | Runs and frontier |
| `decisions_read` | `list`: decisions, and one decision request by `decisionIds` |
| `decisions_write` | `answer` and `ack` on decisions; `withdraw` only a decision this principal raised without a seat |
| `runs_write` | `pause`, `resume` and `close` on a run (below) |
| `report` | `rollup` per run; `usage`, `gates`, `contention`, `waste` on request |
| `items_read` | `blocked` under the project root, for blocked work; `overview` for counts |
| `deps_read` | Dependency `list` / `graph` when a blocker needs explaining |
| `Agent` | Background only (see the altitude rule), with `model` set |
| `SendMessage`, `ListAgents` | Talk to orchestrators and other sessions |
| `Skill` | Any skill (`task-orchestrator:model-effort`, entry skills); each call it makes is guarded |
| `PushNotification` | Tell the user a decision is waiting when they are away |

The MCP tools come from the director profile. Anything else (notes, items writes,
`advance`, config) is an orchestrator's job. `runs_write` `open` is denied: a session that opens a run
holds its token and is its orchestrator.

The guard enforces the altitude and write rules, not this whole budget; only the hardened entry does,
through its tool list.

Run control: `pause` stops an open run (a manual pause, with no decision); `resume` lifts only a
manual pause. A run paused on an `attempt-cap` decision is lifted only by answering
that decision (`allow_one`, `skip_item`, `stop`) or by `close`; `resume` on it answers
`invalid_run_state`. `close` ends a run whatever its pause; a `completed` close is refused while a
seat is still active, and a close leaves open decisions open (its snapshot's `openDecisions` is the number still open).

## Connection check

`/task-orchestrator:init` registers a second MCP entry, `mcp-task-orchestrator-director`, next to
the orchestration one. Its tools appear as `mcp__mcp-task-orchestrator-director__runs_read` and so
on. Confirm they are in your tool list; do not edit `.mcp.json` from here.

This skill uses `decisions_read` and `decisions_write` when the director connection lists `decisions_read`. When it does not (an older server), use the equivalents with the same arguments: `runs_read` `decisions` (no status filter: keep `open` and `acknowledged` yourself) and `decision`; `runs_write` `answer`, `ack` and `withdraw`.

That connection uses the server's director profile (`/mcp/director` over HTTP, or
`MCP_TOOL_PROFILE=director` on stdio), which has no body read, so the server enforces the rule there.
On the orchestration connection the guard enforces the same read rule (the altitude rule above),
telling the two apart by the server key. Use the hardened entry (below) when enforcement matters.

If the director profile's tools are missing, say so and name `/task-orchestrator:init` as the fix. Until
then the director-only members are unavailable on the orchestration connection (`hooks/lib/tool-catalog.json`
lists each tool's profiles). Decisions cannot be answered until init runs.

## Dashboard

Run these in order and render one compact board. Every call below uses the director profile.

1. Open runs, then paused runs (the list takes one `status` per call):

```to-call
{"tool": "runs_read", "select": "list", "args": {"status": "open", "limit": 20}, "profile": "director"}
```

```to-call
{"tool": "runs_read", "select": "list", "args": {"status": "paused", "limit": 20}, "profile": "director"}
```

Show `pauseReason` on each paused run: an attempt pause waits for its decision. No open
or paused run: say so in one line, still show the decisions (step 3) and the blocked work (step 5),
and stop.

2. For each open or paused run, its frontier: `ready` and `waiting` items, and the `inFlight` seats (each
with `stalled`):

```to-call
{"tool": "runs_read", "select": "frontier", "args": {"runId": "7c41e0a2"}, "profile": "director"}
```

3. Decisions under the project root, in one read: it returns run and run-less decisions together, newest first
(follow `page.next`). Group them by `runId`, with a "no run" group. `overdue: true` narrows to the late ones.

```to-call
{"tool": "decisions_read", "select": "list", "args": {"rootId": "ce064d2c", "status": ["open", "acknowledged"], "limit": 50}, "profile": "director"}
```

4. The rollup per open or paused run (counts, stalls). This is one of the two bodies you may read:

```to-call
{"tool": "report", "select": "rollup", "args": {"runId": "7c41e0a2"}, "profile": "director"}
```

5. Blocked work under the project root. Each entry carries `blockType`, `reason`, `decisionId`,
`resumable` and `blockers[]` (`id`, `title`, `role`, `satisfied`); do not open the item's notes. An `awaiting_decision` entry with `resumable: true` was answered and
only needs a resume (the rollup's `blocked.resumable` counts them):

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
## Board
Decisions waiting: 2 (1 overdue)
  [d-3f2a] run 7c41 deviation high "Approve schema change?"   OVERDUE
  [d-91bc] no run   question low   "Pick retry strategy"
Runs: 1 open, 1 paused
  7c41 "W8 plugin"  ready 3  inFlight 2  waiting 1   stalled 0
  9d02 "W9 docs"    PAUSED attempt-cap (d-5e10)
Blocked: 2
  8aee "Director skill" blocked by dependency 4440ff65
  b31c "Api guard"      answered, needs resume (d-77aa)
Orchestrator placement: 2 (Workflow)
```

In Code Mode: `Orchestrator placement: 2 (Workflow), 2b (Code Mode)`.

Add an orchestrator cell to each run's line, from the frontier's `stalled`: show `coord idle (Method A)`
when the run's `hints.method` is `A` and `hints.phase` is `launched` (nothing runs under the
run token during a Workflow), `coord idle, seats live` when any `inFlight` seat has
`stalled: false`, nothing on a paused run, else `COORD STALLED`: `SendMessage` the orchestrator, and
if it is gone, close the run and re-plan.

Then ask the user which decision to take, or stop if none is open.

## Answering a decision

Read the one decision request in full (the other body you may read):

```to-call
{"tool": "decisions_read", "select": "list", "args": {"decisionIds": ["3f2a9c1e"], "includeContext": true}, "profile": "director"}
```

Show the question, the options and any context it carries, and ask the user. Never answer on the
user's behalf unless they told you how to answer this class of decision. Then answer it, with the
user's rationale:

```to-call
{"tool": "decisions_write", "op": "answer", "args": {"decisionId": "3f2a9c1e", "option": "approve", "rationale": "Migration reviewed; table recreation is safe."}, "profile": "director"}
```

An attempt-cap decision is answered with these options:

| Class | Options |
|---|---|
| `attempt-cap` | `allow_one`, `skip_item` or `stop` |
| legacy budget class (stored 4.0.0 runs only) | `extend` or `stop`, with no other field |

Before answering, check the class: `answerers: human-only` cannot be answered from this session
(`forbidden`, `principal_kind` `human`). `ack` marks a decision seen but deferred; it does not answer it.

`text` carries a free-form answer, when the decision asks for one.
On `forbidden`, read `error.detail.scope` and `required`, say which, and stop:

- `scope: "capability"`, `required: "decide"`: this connection's credential lacks the `decide`
  capability.
- `scope: "principal_kind"`, `required: "human"`: a `human-only` decision; it needs a credential
  of kind `human`.
- `scope: "principal_kind"`, `required: "director,human"`: the call carried a `seatToken`, or its credential does not grant `decide` (`run/internal/app/DecisionPermissions.kt:29`).

Withdraw a decision the run no longer needs (for example the user cancelled that work):

```to-call
{"tool": "decisions_write", "op": "withdraw", "args": {"decisionId": "3f2a9c1e"}, "profile": "director"}
```

Withdraw succeeds only for a decision this principal raised without a seat, or under the run
token of the decision's run. With the one `local` principal (stdio, unauthenticated local mode) that covers every decision raised without a seat.
Nobody can withdraw a server-raised attempt-cap decision.
For a seat's decision, `SendMessage` the run's orchestrator to withdraw it, or answer it.
`decision_closed` means someone already answered or withdrew it: refresh the board.

## After the answer

An answer never resumes the item. When the decision has an `itemId`, `SendMessage` the run's orchestrator with
the item id (ids only): it resumes the item and re-dispatches. With no live orchestrator (the run is closed, or the
decision was raised outside a run), dispatch one background `Agent` with an explicit `model` to resume the item
per `/status-progression`. The board then shows the entry as "answered, needs resume" until it does.

## Orchestrator placement

Probe once per entry and state the result as one board line. The tier says where orchestrators
run; it does not launch one. Tiers 1, 2 and 2b show in your own tool list (no calls). Tier 3 needs the
`claude` CLI on the host, which no tool list shows: ask the user, or dispatch one background agent
to run `claude --version` and report. The hardened entry has no shell, so only the agent can.
In Code Mode, probe the tools a script can call (Workflow, Agent, SendMessage), not the REPL and
Poll pair.

| Tier | Present | Meaning |
|------|---------|---------|
| 1 | `start_session` and `SendMessage` (or `send_message`) in your tool list | Orchestrators run as separate sessions; reach them with `SendMessage` |
| 2 | The `Workflow` tool | A run executes as a background workflow; the director makes the one `Workflow` call and relays its args and result, since a background orchestrator may lack `Workflow` |
| 2b | `REPL` and `Agent` (Code Mode) | A Code Mode orchestrator subagent runs the whole run, Method B; its `Agent` prompt is the line `Run /task-orchestrator:run-wave <scope> --method B as a tier-2b orchestrator.`; with 2 also present, 2 for Method A (coordinator-tiers.md) |
| 3 | Neither, but `claude -p` is available on the host | Orchestrators run as headless `claude -p` processes started by a background agent |
| 4 | None of the above | No separate orchestrator: every read and edit goes through background `Agent` dispatches from this session |

At tier 4, warn the user once: the altitude holds only while each delegated agent reports a
summary, not raw file content, so ask each agent for a short summary.

How to launch an orchestrator at each tier is in [references/coordinator-tiers.md](references/coordinator-tiers.md).
Tier 1 `start_session` brief: `Run /task-orchestrator:run-wave <scope> --altitude-safe. Report to
<address> with SendMessage, only the report helper's output, never
"main". Send no note bodies, diffs or tokens. Never ask the user: put any need on the needs: line.
Stop on a director close and report closed.` `<address>`: this session's name, the first `ListAgents`
line (`This session is <name> [<ref>]`); if absent, ask the user.
`scripts/coordinator-tiers.mjs` has the same probe rule and prints each launch profile; run it with
`node` by its absolute path (the plugin folder is this skill's base directory, two folders up). A run of one or two items needs no
separate orchestrator: dispatch one background agent.

## Waking up

A message starting `TO report:` is an orchestrator report (events `planned`, `decision`, `paused`,
`closed`): read the run it names by its id (never search the board for your run), then act on the ids
it names only, then Answering. Tier 2: on `planned` with `needs: approve launch`, get the user's
approval (missing allow rules: name them; the user adds them, accepts the prompts or stops);
then call `Workflow` with the returned `args` and `SendMessage` the orchestrator `launched` with the
call's task id, run id and script path; on the task notification, send it the result JSON verbatim, or
the output file path when the notification is cut short. Tier 2b: on that `planned`, get the approval,
then `SendMessage` the orchestrator `approved` or `stop`. A linked session's turn note with no
`TO report:` needs no board read: note it in one line and wait.

```to-call
{"tool": "runs_read", "select": "get", "args": {"runId": "7c41e0a2"}, "profile": "director"}
```

and the run's decisions (Dashboard step 3). Do not decide on your own whether to push or run a
retrospective: the plugin's hooks tell you. A `Decision waiting:` directive means call
`PushNotification` once for the ids it names. A retrospective directive comes one per completed
scope (per closed run at boundary `run` or without REST) and is always the dispatch form in a
director session, whatever `retrospective.mode` says (`off` still sends none): dispatch the one
background agent it names. At tier 1 the orchestrator's own session closes the run, gets the directive
and runs the retrospective: dispatch none for that run. Never run `/session-retrospective` in this session; it reads note
bodies. Each fires once per decision, scope or run per session.

## Hardened entry

`claude --agent director` starts a session whose tool list is already limited to the director-mode
tool budget (the plugin's `agents/director.md`), which has neither `Workflow` nor `start_session`: it probes tier 3 or 4. Tiers 1, 2 and 2b need director mode in a normal session. It still invokes this skill first, so the marker and
the guard apply too.
