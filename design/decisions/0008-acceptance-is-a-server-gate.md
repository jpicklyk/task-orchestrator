# 0008 Acceptance becomes a server gate; plan approval is a conditional grant

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** [journey-config-fit.html](../journey-config-fit.html) (gap 1, R8); Entry Skills Design calls 11 and 12

## Decision

Acceptance, the user's verdict that an item delivers what was asked, is a gate in the server: an item of a gated type cannot reach terminal without an acceptance record. The gate is opt-in per type, as the review phase is. Every surface satisfies it the same way: plan-mode approval or a plain yes in Claude, or a skill on a harness with no plan mode. Plan approval in Auto mode, given before the work, is a conditional grant: it applies only if the review passed, its findings were disposed, the build is green at HEAD and no decision is open. The server checks what it holds; build green and findings disposed are claims the accept skill records, each build claim naming the commit and the command.

## Context

In Claude acceptance happens today through conversation or plan-mode approval, and nothing records it; on a harness without plan mode there is no path at all. The owner wants the gating supported whatever the surface. The pieces mostly exist: a required note already gates, and a blocking decision under a `human-only` class is already a human gate the server enforces.

## Alternatives rejected

- **Keep acceptance a convention the orchestrator follows.** Leaves other harnesses without it and leaves no record.
- **Let plan approval satisfy the gate outright.** The gate would never stop anything; the conditions keep it meaningful.
- **Gate every item.** A light project would wait on acceptance for every item; opt-in per type keeps it where it earns its cost.

## Consequences

- Merge is armed only on acceptance.
- Over stdio a `human-only` decision cannot be answered, a limitation already accepted.
- Without authentication the server records who said they accepted, not proof that a person did: provenance, not security.

## Form

Settled by [0012](0012-acceptance-gate-note-form.md), which also holds the gate's behaviour.
