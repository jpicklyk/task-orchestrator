# Decision records

One short record per architectural decision, so a later reader does not undo a choice without its reasoning. The format and the test for when a decision earns a record come from the plugin's [decision-records reference](../../claude-plugins/task-orchestrator/skills/defining-project-terms/references/decision-records.md): record a choice when a reader without the reasoning would likely undo it.

## Rules

- A record is edited in place until a release ships it, so each decision reads in one place; an in-place change adds a dated "Amended" line under the record's header saying what changed and why. After a release ships it, a later decision supersedes it: the old record is marked `Superseded by NNNN` and both link to each other.
- Work that would conflict with an accepted record is raised with the owner first, not decided silently.
- A pure term decision belongs in a [GLOSSARY.md](../../GLOSSARY.md) entry. A record is needed only when the reason does not fit there.
- Design documents in [design/](../README.md) describe current intent and link here for the why. They do not restate a decision.
- Each record names its source: the PR, item or design document where it was decided. Where the server holds a decision record for the same choice, cite its id.

## Format

File `NNNN-short-title.md`, numbered in order. Fields: **Status** (Accepted, Superseded by NNNN, or Proposed), **Date**, **Source**, then **Decision** (one or two sentences), **Context** (the force behind it), **Alternatives rejected**, **Consequences**, and **Reopen when** if there is a known trigger.

## Index

| # | Decision | Status | Date |
|---|---|---|---|
| [0001](0001-two-session-layers.md) | Two session layers: the orchestrator drives, the director decides; "coordinator" is retired; the rules across the layers | Accepted, amended 2026-11-26 | 2026-11-23 |
| [0002](0002-director-on-orchestrator-tools.md) | The director uses the orchestrator's tools, with decision ops on a second connection until 0003 lands; the guard hook alone enforces altitude | Accepted, amended 2026-11-26 | 2026-11-23 |
| [0003](0003-role-neutral-decisions-surface.md) | Decisions get a role-neutral tool surface; authority comes from class and credential | Accepted | 2026-11-23 |
| [0004](0004-next-means-startable.md) | `next` returns an item a build seat can start now | Accepted, amended 2026-11-26 | 2026-11-23 |
| [0005](0005-renames-with-deprecated-aliases.md) | Renames use canonical names now, with deprecated aliases removed in 5.0 | Accepted | 2026-11-23 |
| [0006](0006-method-words-are-schema-vocabulary.md) | Method words (epic, story, charter, increment) are schema vocabulary, not tool concepts | Accepted | 2026-11-24 |
| [0007](0007-certainty-is-judgement.md) | The certainty dial is judgement held in conversation, not config | Accepted | 2026-11-24 |
| [0008](0008-acceptance-is-a-server-gate.md) | Acceptance becomes a server gate; plan approval is a conditional grant | Accepted, amended 2026-11-26 | 2026-11-24 |
| [0009](0009-setup-applies-tested-templates.md) | Setup applies one pre-built, tested template from a reference library | Accepted | 2026-11-24 |
| [0010](0010-two-domains.md) | Two domains: software development and keeping a knowledge base | Accepted | 2026-11-24 |
| [0011](0011-no-sync-with-outside-tools.md) | No synchronisation with outside tools | Accepted | 2026-11-24 |
| [0012](0012-acceptance-gate-note-form.md) | The acceptance gate is a note satisfied by an answered decision | Accepted, amended 2026-11-26 | 2026-11-24 |
| [0013](0013-no-sibling-rank-yet.md) | No sibling rank until a need appears | Accepted, amended 2026-11-26 | 2026-11-24 |
| [0014](0014-director-reads-skills-runs-plugin-scripts.md) | The director reads skill and plugin files and runs a plugin's scripts (amends 0002) | Accepted, amended 2026-11-26 | 2026-11-26 |
| [0015](0015-note-purpose.md) | The note purpose field and its values; acceptance evidence lives on the decision | Accepted | 2026-11-26 |
| [0016](0016-seat-capability-and-orchestrator-notes.md) | Seats declare `writes`; the protocol rule is chosen from `enters` and `writes`; `kind` held; the orchestrator is never a seat (orchestrator notes) | Accepted | 2026-11-26 |
| [0017](0017-orchestrator-binding.md) | The orchestrator binding at config root and trait level | Accepted | 2026-11-26 |
| [0018](0018-content-placement.md) | Content placement: the layers and concepts, binding, and the two rule classes | Accepted | 2026-11-26 |
| [0019](0019-learn-reads-upstream-sources.md) | Learn reads Task Orchestrator's docs, glossary and design from GitHub at the installed version, plus the user's served schema and config; nothing is bundled | Accepted | 2026-11-26 |
