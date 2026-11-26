# 0014 The director reads skill and plugin files and runs a plugin's scripts

**Status:** Accepted | **Date:** 2026-11-26 | **Source:** item `d98e684b` (PR #804), the owner's rulings in the session after the 2026-11-26 director trial (run `5a59531f`); amends [0002](0002-director-on-orchestrator-tools.md)

## Decision

Under the director marker, `Read` may open any file inside a skill (a folder that holds a `SKILL.md`: its references, scripts and other files) and any file inside an installed plugin (an ancestor folder holds `.claude-plugin/plugin.json`). `Bash` may also run `node` on a script inside an installed plugin. Both checks resolve links first and refuse a `..` segment. Every other file stays closed to the director, and [0002](0002-director-on-orchestrator-tools.md)'s read, write and skill lines are unchanged.

## Context

0002 made the guard the one place altitude is enforced, and it denied every file read. A director could then not follow its own skill's references, so everything it needed had to sit in one `SKILL.md` under an arbitrary 18000-byte cap. In the trial that left the tier-1 brief, kept in a reference file, unreadable to the director, and the orchestrator it launched sent no report. Skill and plugin files are instructions and deterministic helpers, not work content, so reading them does not break the lean-context reason for altitude. A plugin's scripts give the director deterministic routing and decisions (for example the placement probe), which the owner wants it to run itself.

The skill size test now follows Anthropic's Agent Skills best practices: a `SKILL.md` body under 500 lines, `name` and `description` limits, references linked directly from `SKILL.md`, and a table of contents on a reference over 100 lines.

## Alternatives rejected

- **Only the director skill's own references.** Other skills a director loads would still be unfollowable.
- **Markdown only.** The owner ruled scripts in as routing and decision helpers.
- **A deny list of plugin subcommands that print note bodies** (for example `run-planner snapshot`). Held back until a real case shows the need; the owner chose the wider rule first.

## Consequences

- A plugin script that prints note bodies can put them in the director's context. If that happens in practice, narrow the rule with a subcommand deny list.
- Follow-up `ef650431` (read-only helper scripts) is resolved by this record.

## Reopen when

A plugin script that the director runs puts a note body, diff or test output in its context, or a host stores plugins without `.claude-plugin/plugin.json`.
