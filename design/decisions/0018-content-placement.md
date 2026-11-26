# 0018 Content placement: every statement has one layer and one concept

**Status:** Accepted | **Date:** 2026-11-26 | **Source:** Boundary analysis 2026-11-26, owner answers (plans/boundaries/README.md, not tracked), decisions 13 and 20; the former root `CLAUDE.md` section "Domains and responsibilities", whose substance this record took over

## Decision

Task Orchestrator mechanics and a project's process are separate domains. The plugin encodes how Task Orchestrator works and ships generic engineering skills as defaults; a project's process lives in config it can override (note schemas with `guidance` and `skill:`/`skillPointer`, seat `skills:`, traits, the orchestrator binding, served rules, run-profile, `dispatch-policy.json`). Only the schema defines the workflow's structure: which types, phases, notes and seats exist and in what order. A rule governs a seat's or the orchestrator's conduct inside that workflow.

Each piece of text lives in exactly one layer, and each sentence makes the kind of statement exactly one concept may make. A process change goes to the layer that owns it: schema (the workflow's structure), rule (conduct) or run-profile first; skill or plugin text only for mechanics or generic engineering practice.

### Layers: where text lives

| Layer | Holds | Never holds |
|-------|-------|-------------|
| Plugin mechanics: skills, agents, workflows and hook text (`claude-plugins/task-orchestrator/`), engineering skills excepted | Task Orchestrator mechanics (tools, phases, gates, runs, seats, dispatch, hooks); host integration (Agent `model`/`effort`, ToolSearch, plan mode, `AskUserQuestion`); pointers to rules and schema fields | A named Claude Code or Anthropic skill; a project's paths, commands or conventions; process steps a project could choose differently |
| Plugin engineering skills (the `claude-plugins/task-orchestrator/skills/` directories that carry generic engineering practice rather than Task Orchestrator mechanics, whether or not the skill defaults table, `claude-plugins/task-orchestrator/hooks/lib/seat-skill-defaults.json`, names them) | Generic engineering practice for a seat's work (planning, implementing, reviewing, test authoring) or for an engineering task a user runs (auditing code, keeping a glossary), overridable per project through a binding | Anything specific to one repo; a named Claude Code or Anthropic skill |
| Bundled rules (`claude-plugins/task-orchestrator/bundled-rules/`) | The protocol rules' text, and generic conduct defaults (seat defaults and the orchestrator's defaults), overridable per project | Anything specific to one repo |
| Project config and rules (`.taskorchestrator/`) | This project's process: schemas, traits, the orchestrator binding, rules, run-profile, and the Claude Code skills a binding points to | - |
| Project skills (`.claude/skills/`) | Repo mechanics (release, Gradle lock, migrations, deploy), and the skills a binding points to | Copies of rules or schema guidance; decisions the schema makes (which notes exist, which phases are reviewed) |

A Claude Code or project skill is reached only through a binding (a seat's `skills:`, a note's `skill:` or `skillPointer`, the orchestrator binding's `skills`), never named in plugin or orchestration-skill prose; the plugin's own engineering skills reach a seat as its defaults. [target-architecture.md](../target-architecture.md) applies the same split to the whole system as zones; this table is the part an author of plugin, config or skill text meets.

### Concepts: what kind of statement

A sentence belongs to exactly one concept, and no concept is described in terms of another. The plugin's mechanics skills sit in the plugin mechanics layer and its engineering skills in the plugin engineering skills layer.

| Concept | Holds | Never holds |
|---------|-------|-------------|
| Mechanics skill (tool usage) | How Task Orchestrator works: tools, phases, gates, runs, seats, dispatch; how to read a schema and act on what it says | What a project's workflow should be; engineering craft |
| Engineering skill | How to do one seat's work well: knowledge, criteria, method; it may order the work inside its own craft | Tool mechanics (phases, gates, seats, hand-offs, hooks); when it is loaded or who checks it |
| Rule | A short must-do for a seat's or the orchestrator's conduct | Engineering depth; workflow |
| Schema | The workflow: which types, phases, notes and seats exist, their order, and which skill or rule is bound to which seat or note; with its traits, what is bound to the orchestrator | How to do the work; enforcement |
| Hook | Timing and enforcement in the client: hint, record, refuse; it reads what the schema says | Engineering content; workflow decisions |
| Server | State and gates: a note exists, a seat owns it, a transition is allowed; the tool contract: what each tool and parameter does and when to call it | Engineering craft; a project's workflow decisions |

### The sentence test

A sentence that says what good work is, or how a seat goes about its craft, is an engineering skill. One that explains how to use the tools to run the work is a mechanics skill. One that says what one tool or parameter does, or when to call it, is the server's tool contract. One that says which phases, notes or seats exist, their order, or what is bound to what, is schema. A short must-do on any project using the seat is a rule. When something is hinted, checked or blocked is a hook, in code.

Example, test blindness: how to derive a test's expected result from the specification rather than from the code is an engineering skill; that the test plan is a queue note the planner seat fills, so it exists before any implementation, is the schema; when that skill is hinted, or a note write made without it is refused, belongs to a hook.

### Binding and rule classes

Binding is declared in config, from these sources:

- **For a seat:** the seat entry's `rules:` and `skills:`, and a note's `skill:` or `skillPointer`, in the project's schema.
- **For the orchestrator:** the orchestrator binding, the `orchestrator:` block (`rules`, `skills`) at config root and on a trait; a schema reaches a trait's binding through `default_traits` ([0017](0017-orchestrator-binding.md)). The root list replaces the plugin's orchestrator defaults; a trait's list adds to them for an item that carries the trait.
- **Where config is silent:** the plugin's defaults tables, which are data only (default keys by seat name, and an `orchestrator` row: rule defaults in `claude-plugins/task-orchestrator/hooks/lib/seat-rules.json`, skill defaults in `claude-plugins/task-orchestrator/hooks/lib/seat-skill-defaults.json`), never prose in a skill or a hook. A seat whose name has no defaults row gets no defaults. A declared list always replaces the table's defaults for that seat.

A rule key reaches a seat or the orchestrator in exactly one of two classes, from one plugin table (`claude-plugins/task-orchestrator/hooks/lib/seat-rules.json`):

- **Protocol rule:** a rule the tool's own machinery breaks without. It is tool mechanics outside binding, chosen by the plugin from the seat's served facts, `enters` and `writes` ([0016](0016-seat-capability-and-orchestrator-notes.md)), and for the orchestrator `protocol.orchestrator`. Every writing seat gets `protocol.commit`: the item tag, the `Seat:` trailer and path-scoped commits, the commit form verify reads. No schema or declared list removes a protocol rule; a declared protocol key that does not match the seat's facts is dropped and reported. A project may replace a protocol rule's text, and its copy then stops receiving plugin updates.
- **Seat default:** applies only where the seat declares no `rules` list, which replaces it. `review-scoping` is one; `commit-message` (subject style, `Co-Authored-By`) is one on the defaults rows of the seats that write.

Conduct a project adds goes in a project rule on the seat's `rules` list or in the orchestrator binding. Note guidance says what a note must record and in what shape; how to do the work belongs in the skill bound to the note or seat.

**Today (origin/main ce75ca2a8), not yet the above:** the plugin derives a role from the seat's name and phase (`seatRoleOf`, `claude-plugins/task-orchestrator/hooks/lib/seat-rules.mjs:58`) and keys protocol and defaults by that role; `seat-rules.json` carries a third column, `pluginChosen`, whose one member is `commit-discipline`, added for the writing roles (`seat-rules.json:10`, `:15`, `:30`); the orchestrator binding does not exist, and the orchestrator's conduct reaches it as prose in context hooks and skills.

## Context

These tables sat in the root `CLAUDE.md` as guidance for agents working in this repository, but they are an architectural decision: they decide where every change to plugin, config and project-skill text goes, and a guard test (`claude-plugins/task-orchestrator/hooks/tests/contract-domain-boundary.test.mjs`) enforces part of them. An instruction file is the channel for repo facts, traps and guard rules; a decision kept there has no recorded reason, no alternatives and no supersede path, and it costs context in every session. The layer test is also needed by users who edit their own process (journey stage 5.4), and this repository's `CLAUDE.md` does not ship.

The boundary analysis used the tables as its test across ten passes and found two gaps. The third rule class had one member, added exactly when a seat writes, so it was chosen from the same fact as the protocol rule; once `commit-discipline` split (decision 20), its mechanical half met the protocol definition word for word and its style half was a replaceable default. And the orchestrator's conduct had no binding: it was the only actor whose rules and skills could not be declared, though it is the only one present before an item exists.

## Alternatives rejected

- **Keep the tables in `CLAUDE.md`.** The decision stays in a channel meant for repo facts, without its reasons, and every session pays for it.
- **Keep a third rule class for rules the plugin adds whatever the schema says.** Its one member is chosen from `writes`, as a protocol rule is; a class to learn for one rule, which the commit split removes.
- **Bind the orchestrator's conduct on a seat entry.** The orchestrator is never a seat ([0016](0016-seat-capability-and-orchestrator-notes.md)); its binding sits at root and trait level ([0017](0017-orchestrator-binding.md)).
- **Only a shipped plugin reference for the layer test.** The reference carries how a process change finds its layer; the reason the layers divide this way belongs in a record.

## Consequences

- Root `CLAUDE.md` keeps a two-sentence summary of the layers and concepts and a pointer to this record. Today's three rule classes are stated in [config-reference.md](../../current/docs/config-reference.md) "Seat rules" until the two-class change (P12 in [architecture-breakdown.md](../architecture-breakdown.md)) lands.
- `seat-rules.json` loses its third column and becomes a capability table plus defaults rows by seat name and an `orchestrator` row; `commit-discipline` splits into `protocol.commit` and the seat default `commit-message` under the bundled-rules manifest. Plugin only, no server change.
- A shipped mechanics reference for the layer test (target) lets a user's process change find its layer without this repository's `CLAUDE.md`.
- The domain-boundary guard test still checks only naming in the plugin tree; whether a sentence sits in the right concept stays a review judgement.
- Where the seat facts, defaults and orchestrator binding are resolved: [seat-model.md](../seat-model.md) and [config-layering.md](../config-layering.md).

## Reopen when

A rule appears that the plugin must add whatever the schema says but that the tool's machinery does not break without, or a second host needs the layers split differently.
