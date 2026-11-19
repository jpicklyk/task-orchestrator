# Director guard coverage

This page is for the user and for maintainers. A director session cannot read it: the guard denies `Read` under the marker, so a director does not retry.

## What the guard denies

In a skill-entered session the guard denies the file and shell tools, foreground `Agent`, `Bash` outside its read-only allow-list, the `config` tool (every op, `push`, `rollback` and `reset` included) and the reads that return note or document bodies.
It does not deny the orchestration connection's other writes (`notes_write`, `items_write`, `advance`).
Only the hardened entry enforces the whole budget, through its tool list.

## How it tells servers apart

The limit: the guard recognises the orchestration connection by `task-orchestrator` in its server key (`HOST_TOKEN`, `hooks/director-guard.mjs:92`), so a server registered under a key without that word is treated as a host server and denied outright under the marker, and a server whose key contains `task-orchestrator` is treated as the Task Orchestrator server, so only its listed reads are guarded.

## Flag lists

`SKILL.md` names the common flags only. The whole lists are the guard's constants in `hooks/director-guard.mjs`: `GIT_LOG_FLAGS`, `GIT_LOG_VALUE_PREFIXES`, `GIT_STATUS_FLAGS`, `GIT_BRANCH_LIST_FLAGS` and the `gh pr` sets.
