# 0010 Two domains: software development and keeping a knowledge base

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** [user-journey-map.html](../user-journey-map.html) journey 8; [evidence/journey-knowledge-base.md](../evidence/journey-knowledge-base.md)

## Decision

Task Orchestrator supports two domains. Software development is primary and remains the design centre. Keeping a knowledge base (capture, link, find, revisit and synthesise notes and documents, with no phases, sign-off or builds) is first class, served by the knowledge tool profile (`/mcp/knowledge`). Intake and operations is not a supported domain.

## Context

The hooks, seat defaults, sizing tiers and setup were all written for software. A guard pass over other domains showed the knowledge base is real usage with its own endpoint, while intake and operations is not used with this tool. The knowledge pass found 29 places where the development design leaks into a knowledge session; setup fails outright on a knowledge connection.

## Alternatives rejected

- **Development only.** Would keep breaking the knowledge endpoint's real users.
- **Treat other domains as guard journeys only.** Right for unused domains; not enough for one with real users and its own endpoint.
- **Support intake and operations.** Not used; designing for it adds cost with no user.

## Consequences

- The development design must keep the 14 guard rails on the [Journey Config Fit](../journey-config-fit.html) page, for example: a knowledge session gets no sizing tiers or dispatch rules from any hook, and no lifecycle-free item is counted as backlog.
- Knowledge entries are lifecycle-free items, the only coherent base on an endpoint with no `advance`.
- No new phase, gate, seat, decision class or detector is added to serve the knowledge base.
