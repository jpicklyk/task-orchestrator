# 0005 Renames use canonical names now, with deprecated aliases removed in 5.0

**Status:** Accepted | **Date:** 2026-11-23 | **Source:** [glossary-alignment.md](../glossary-alignment.md) section C (C1 revised); Entry Skills Design review

## Decision

When the glossary renames a concept, code and the tool contract take the canonical name now. The old name stays as a deprecated alias through 4.x, behind a thin legacy adapter, and is removed in 5.0. Responses carry both keys while the alias lives. A storage column keeps its old name (for example `coordinator_seen_at`), since renaming it buys nothing.

## Context

Old names such as `coordinatorToken`, `RunSeat.COORDINATOR` and the `runs_*` decision ops contradict the glossary ([0001](0001-two-session-layers.md), [0003](0003-role-neutral-decisions-surface.md)). Keeping them forever teaches every new reader the retired model; removing them in a minor release breaks clients.

## Alternatives rejected

- **Keep the old names in code permanently, documented as historical.** Every reader of the code would meet the retired vocabulary.
- **Rename in place with no alias.** Breaks 4.x clients mid-major.

## Consequences

- Each rename lists its alias as deprecated in the tool contract and the changelog.
- The 5.0 release removes every alias and its adapter in one sweep.
- The hook config key `orchestration.mode` is renamed `steering` the same way, with the old key still read.
