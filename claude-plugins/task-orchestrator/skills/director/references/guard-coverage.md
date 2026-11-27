# Director guard coverage

This page is for the user, for maintainers and for a director session, which may `Read` skill and plugin files under the marker.

## What the guard denies

This page is the one full description of what `hooks/director-guard.mjs` allows under the director marker; the guard's
header comment, the director `SKILL.md`, the hooks README and the integration guide summarise it and point here. Where
they disagree, the guard's code is right and this page is fixed.

The guard enforces the altitude rule and the write rule, not this whole budget: in a skill-entered session it
denies the file and shell tools, foreground `Agent`, `Bash` outside its allow-list, the `config` tool
(every op, `push`, `rollback` and `reset` included), the reads that return note or document bodies
(`notes_read` and `docs_read` `get`, `items_read` `search` unless `snippet: false`, and `schema` with
`fields: ["guidance"]`; the list, history, links and `related` reads run, since they return no body text), and every Task Orchestrator
write that drives work: `advance`, `items_write`, `notes_write`, `deps_write`, `docs_write`, and `runs_write`
unless every op of the call is `answer`, `ack`, `withdraw`, `pause`, `resume`, `close` or `reissue` (so `open`,
`update`, `mark`, `assign`, `end_seat` and `raise` deny). `decisions_write` runs only `ack`, `answer`, `delegate`, `escalate` and `withdraw`, so its `raise` denies. `describe: true` passes on every one of them. `Skill`
is never denied: loading a skill only adds instructions, and each tool the skill calls is checked on its own call.
Only the hardened entry enforces the whole budget, through its tool list.

Tool by tool (constants in `hooks/director-guard.mjs`):

| Tool | Under the marker |
|------|------------------|
| `Read` | Only a skill's files or an installed plugin's files (below). |
| `Edit`, `Write`, `Grep`, `Glob`, `NotebookEdit` | Denied. |
| `PowerShell`, `Monitor` | Denied: each prints a shell command's output, and only `Bash` has an allow-list. |
| `Bash` | Every segment starts with `git status`, `git log`, `git branch`, `gh pr view`, `gh pr list` or `docker ps` (`DIRECTOR_BASH_ALLOW`), or is `node` on an installed plugin's script by its absolute path. No redirection, substitution, braces, globs or backslashes. `git log`, `git status` and `git branch` take only the flags on their flag allow-list, so none prints a patch, a diff or file contents; `gh pr view` and `gh pr list` take only operands, `-R` or `--repo`, `--json` with fields from `GH_PR_JSON_FIELDS` (status, metadata and `body`) and, for `list`, the listing filters. |
| `Agent` | Only with `run_in_background: true`, or in Code Mode (after a main-session `REPL` call) or the desktop app (`CLAUDE_CODE_ENTRYPOINT` `claude-desktop`), where every `Agent` call runs in the background. |
| `Workflow` | Only `task-orchestrator:implement-wave` with an object `args` carrying `altitudeSafe: true`, never with a `scriptPath` or `resumeFromRunId`. |
| `WebFetch`, `WebSearch`, `ReadMcpResourceTool`, `ReadMcpResourceDirTool`, `Artifact`, `ArtifactData`, `ArtifactComments`, `TaskOutput`, `LSP` | Denied (`CONTENT_TOOLS`). |
| `config` | Denied. |
| `items_read` | `get`, `list`, `overview`, `blocked`, `context`, `next`, `history`, `related`; `search` with `snippet: false`; `schema` without `guidance`. |
| `notes_read` / `docs_read` | `list`; for documents also `history` and `links`. |
| `runs_read` | `get`, `list`, `frontier`, `decisions`, `decision`. |
| `decisions_read` | Not matched by the guard: `list` runs. |
| `runs_write` | Every op one of `answer`, `ack`, `withdraw`, `pause`, `resume`, `close`, `reissue`. |
| `decisions_write` | Every op one of `ack`, `answer`, `delegate`, `escalate`, `withdraw`. |
| `advance`, `items_write`, `notes_write`, `deps_write`, `docs_write` | Denied. |
| Host MCP tools (a name without `task-orchestrator`) | Only `start_session`, `send_message`, `update_plan`, `wrap_up`, `mark_chapter`, `archive_session`, `get_settings`, `set_session_output_style` (`DIRECTOR_HOST_MCP_ALLOW`). |
| `Skill`, `SendMessage`, `ListAgents` | Never denied. |

A call from a subagent (`agent_id` present) is exempt, and a headless ralph iteration is never guarded. Under the
marker the guard fails closed: an internal error is a deny. Without the marker it does nothing.

## Skill and plugin files

The file reads it allows are `Read` of a skill's files (any file in a folder that holds a `SKILL.md`,
`isSkillFile`) and of any file inside an installed plugin (`isPluginFile`); `Bash` also runs `node` on an installed
plugin's script. A plugin is installed when the nearest folder above the file that holds `.claude-plugin/plugin.json`
lies outside the session's work tree: the git work tree of the hook input's `cwd` and of `CLAUDE_PROJECT_DIR`, and,
from a linked worktree, its main checkout. The desktop copy, the marketplace cache and a synced copy qualify; a
plugin's source in the repository the session works in does not, since it is work content. The one exception is the
plugin the guard itself was loaded from, which the host runs, so a `--plugin-dir` checkout of it keeps its own scripts.
Both checks resolve links first and refuse a `..` segment, and a script runs only by its absolute path. Skill and plugin
files are instructions and deterministic helpers, not work content. The guard does not check what a plugin script does:
some write files or drive work (the `gen-*` generators, `resolve-changelog --out`, `ralph-loop`), so for scripts the
never-drives rule rests on the director's conduct (record 0014).

## How it tells servers apart

The limit: the guard recognises the orchestration connection by `task-orchestrator` in its server key (`HOST_TOKEN`, `hooks/director-guard.mjs:92`), so a server registered under a key without that word is treated as a host server and denied outright under the marker, and a server whose key contains `task-orchestrator` is treated as the Task Orchestrator server, so only its listed reads are guarded.

## Flag lists

`SKILL.md` names the common flags only. The whole lists are the guard's constants in `hooks/director-guard.mjs`: `GIT_LOG_FLAGS`, `GIT_LOG_VALUE_PREFIXES`, `GIT_STATUS_FLAGS`, `GIT_BRANCH_LIST_FLAGS` and the `gh pr` sets.
