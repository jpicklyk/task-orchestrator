# 0008 Acceptance becomes a server gate; plan approval is a conditional grant

**Status:** Accepted | **Date:** 2026-11-24, amended 2026-11-26 | **Source:** [journey-config-fit.html](../journey-config-fit.html) (gap 1, R8); Entry Skills Design calls 11 and 12. Amendment: Boundary analysis 2026-11-26, owner answers (plans/boundaries/README.md, not tracked), decision 17, and pass 10 decision G1 (same file).

**Amended 2026-11-26:** "merge is armed only on acceptance" is narrowed to gated types; on an ungated type merge is armed by the project's own merge step, as today; the run profile's post-merge steps run after that merge. Why: the gate is opt-in per type, so on an ungated type there is no acceptance record to wait for, and the sentence read as if every merge waited on one. Also amended 2026-11-26 (pass 10, decision G1): a grant given before the work counts only when the user's own words say so, on every host; it is no longer read from Claude Code's permission mode, and plan approval alone is not a grant. Why: reading a host mode gave two behaviours by host, against "every surface satisfies it the same way", and had a hook judge a user's decision from a fact that is not the user's words ([0007](0007-certainty-is-judgement.md)).

## Decision

Acceptance, the user's verdict that an item delivers what was asked, is a gate in the server: an item of a gated type cannot reach terminal without an acceptance record. The gate is opt-in per type, as the review phase is. Every surface satisfies it the same way: plan-mode approval or a yes in Claude, or a skill on a harness with no plan mode. A grant given before the work, with the plan, is a conditional grant, and it counts only when the user's own words say so (for example "yes, and accept on green"), on every host: approving a plan, a plain yes to it or a host permission mode never makes one. A conditional grant applies only if the review passed, its findings were disposed, the build is green at HEAD and no decision is open. The server checks what it holds; build green and findings disposed are claims the accept skill records, each build claim naming the commit and the command.

## Context

In Claude acceptance happens today through conversation or plan-mode approval, and nothing records it; on a harness without plan mode there is no path at all. The owner wants the gating supported whatever the surface. The pieces mostly exist: a required note already gates, and a blocking decision under a `human-only` class is already a human gate the server enforces.

## Alternatives rejected

- **Keep acceptance a convention the orchestrator follows.** Leaves other harnesses without it and leaves no record.
- **Let plan approval satisfy the gate outright.** The gate would never stop anything; the conditions keep it meaningful.
- **Read the grant from the host's permission mode** (entry-skills call 11 as first decided). Two behaviours by host, and a hook would judge the user's decision from a host setting.
- **Treat every plain yes on a gated type as a conditional grant.** It removes the user's verdict silently, and nothing records that the user meant to waive acceptance ([0007](0007-certainty-is-judgement.md)).
- **Gate every item.** A light project would wait on acceptance for every item; opt-in per type keeps it where it earns its cost.

## Consequences

- On a gated type, merge is armed only on acceptance. On an ungated type, merge is armed by the project's own merge step, as today; the run profile's `postRun` steps with `when: post-merge` run after that merge and are never a merge gate.
- A plan proposal on a gated type offers the grant as one plain choice (for example "yes" or "yes, and accept on green"), and the orchestrator records the user's words as the answer ([0012](0012-acceptance-gate-note-form.md)). In Claude Code the permission mode may at most pick which choice is offered first. A project that wants no advance grants declares a `human-only` acceptance class.
- Over stdio a `human-only` decision cannot be answered, a limitation already accepted.
- Without authentication the server records who said they accepted, not proof that a person did: provenance, not security.

## Form

Settled by [0012](0012-acceptance-gate-note-form.md), which also holds the gate's behaviour.
