# Director guard coverage

This page is for the user, for maintainers and for a director session, which may `Read` skill and plugin files under the marker.

## What the guard denies

The guard enforces the altitude rule and the write rule, not this whole budget: in a skill-entered session it
denies the file and shell tools, foreground `Agent`, `Bash` outside its allow-list, the `config` tool
(every op, `push`, `rollback` and `reset` included), the reads that return note or document bodies
(`notes_read` and `docs_read` `get`, `items_read` `search` unless `snippet: false`, and `schema` with
`fields: ["guidance"]`; the list, history, links and `related` reads run, since they return no body text), and every Task Orchestrator
write that drives work: `advance`, `items_write`, `notes_write`, `deps_write`, `docs_write`, and `runs_write`
unless every op of the call is `answer`, `ack`, `withdraw`, `pause`, `resume`, `close` or `reissue` (so `open`,
`update`, `mark`, `assign`, `end_seat` and `raise` deny). `describe: true` passes on every one of them. `Skill`
is never denied: loading a skill only adds instructions, and each tool the skill calls is checked on its own call.
Only the hardened entry enforces the whole budget, through its tool list.
The file reads it allows are `Read` of a skill's files (any file in a folder that holds a `SKILL.md`,
`isSkillFile`) and of any file inside an installed plugin (an ancestor folder holds `.claude-plugin/plugin.json`,
`isPluginFile`); `Bash` also runs `node` on a plugin's script. Both checks resolve links first and refuse a `..`
segment, and a script runs only by its absolute path. Skill and plugin files are instructions and deterministic helpers, not
work content. The guard does not check what a plugin script does: some write files or drive work (the `gen-*` generators,
`resolve-changelog --out`, `ralph-loop`), so for scripts the never-drives rule rests on the director's conduct (record 0014).

## How it tells servers apart

The limit: the guard recognises the orchestration connection by `task-orchestrator` in its server key (`HOST_TOKEN`, `hooks/director-guard.mjs:92`), so a server registered under a key without that word is treated as a host server and denied outright under the marker, and a server whose key contains `task-orchestrator` is treated as the Task Orchestrator server, so only its listed reads are guarded.

## Flag lists

`SKILL.md` names the common flags only. The whole lists are the guard's constants in `hooks/director-guard.mjs`: `GIT_LOG_FLAGS`, `GIT_LOG_VALUE_PREFIXES`, `GIT_STATUS_FLAGS`, `GIT_BRANCH_LIST_FLAGS` and the `gh pr` sets.
