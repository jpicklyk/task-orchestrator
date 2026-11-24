# 0003 Decisions get a role-neutral tool surface

**Status:** Accepted | **Date:** 2026-11-23 | **Source:** [glossary-alignment.md](../glossary-alignment.md) section A; Entry Skills Design "Target interfaces"; [GLOSSARY.md](../../GLOSSARY.md) "Decision", "Decision class"

## Decision

Decisions move to their own tools, `decisions_read` (`list` only) and `decisions_write` (`raise`, `ack`, `delegate`, `escalate`, `answer`, `withdraw`), served on every profile that needs them. Who may answer comes from the decision's class and the caller's credential, never from the role or profile making the call. A decision stays a server record; a question planned in advance is an ordinary item.

## Context

Decision ops lived on `runs_write` and `runs_read`, and answering was possible only on the director profile. That tied an answer to one role, so in orchestration mode the user's own answer had to travel through a director entry, and the names said "runs" for something that also belongs to items and roots.

## Alternatives rejected

- **`decisions_read get` for one decision with its class policy.** Inefficient for agents: one `list` filtered by item, ancestor, run, root, ids, status, class or severity returns full records, with each class policy once per response.
- **A decision as a work item with a trait.** A decision needs its own record (options, answer, who decided, basis); an item that asks something is planned work, and settling it usually raises a decision.

## Consequences

- An answer records `decidedBy`, `basis`, `relayed`, `withinAutonomy` and evidence links. An answer never resumes the item; the orchestrator does.
- The old `runs_*` decision ops stay listed as deprecated in 4.x behind a thin adapter ([0005](0005-renames-with-deprecated-aliases.md)).
- Item resume is refused while its decision is pending.
