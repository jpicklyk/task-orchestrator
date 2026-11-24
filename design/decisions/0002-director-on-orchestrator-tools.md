# 0002 The director uses the orchestrator's tools; the guard hook enforces altitude

**Status:** Accepted | **Date:** 2026-11-23 | **Source:** Entry Skills Design review (option C); [GLOSSARY.md](../../GLOSSARY.md) "Director mode", "Director profile"

## Decision

Director mode runs on the same connection and tools as the orchestrator. The `director-guard` hook is the one place altitude is enforced: it denies body reads and edits, and allows read-only scripts and background dispatch. The director tool profile (`/mcp/director`) stays served, and the hardened director agent stays available, but neither is the default entry any more.

## Context

Director mode was hardened three ways: the director profile, the guard hook and the hardened agent's tool list. Together they forced every entry skill into background delegates, blocked helper scripts, and left it unproven whether delegates could reach the orchestration connection. The owner's aim for the director is to offload research to a background agent and answer from its concise result; the hardening fought that.

An earlier ruling (2026-11-15) kept the `/mcp/director` endpoint for flexibility, other platforms and large deployments. This decision narrows that ruling and does not reverse it.

## Alternatives rejected

- **Keep the three-way hardening as the default.** It made every other entry skill unusable from director mode.
- **Remove the director endpoint.** Rejected on 2026-11-15 and not reopened: other platforms and deployments still need an altitude-limited tool set.

## Consequences

- Altitude stays the rule; only the enforcement point moves to one hook.
- The director path is not a security boundary. Decision authority lives in a credential's capabilities and scope ([0003](0003-role-neutral-decisions-surface.md)).

## Reopen when

A host without hooks needs director mode, so the guard cannot run there.
