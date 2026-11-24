# 0001 Two session layers: the orchestrator drives, the director decides

**Status:** Accepted | **Date:** 2026-11-23 | **Source:** glossary PRs #754 and #757; [GLOSSARY.md](../../GLOSSARY.md) "Session layers"; root `CLAUDE.md` "Session Layers"

## Decision

Above the seats there are exactly two session layers. The orchestrator drives work (plans, sizes, dispatches seats, advances, resumes, carries decisions up) and never answers a decision. The director decides on the user's behalf within each decision class's authority and never drives. "Coordinator" is retired as a concept: an orchestrator scoped to one run is still an orchestrator.

## Context

The original design had three tiers (director, run coordinator, seats), and "orchestrator" meant the whole plugin side. In practice the user's own session and a background agent handed a run did the same job, so "coordinator" and "orchestrator" named one thing twice and confused every discussion of the layers. The director exists for four reasons: a lean long-lived context, one decision inbox across runs, staying free while work runs, and more parallelism than one session can hold.

## Alternatives rejected

- **Keep three named layers.** The coordinator and the orchestrator did the same work; a third name only added confusion.
- **"Lead" and "run lead".** Considered and dropped: both describe the orchestrator.
- **Let the orchestrator answer routine decisions.** Driving and deciding stay apart, so the user (or the director) always owns the answer.

## Consequences

- Session modes are orchestration mode (the default: the user's session is the orchestrator and the user decides) and director mode. An open run is an indicator, not a mode.
- The run token is still named for the old term in code (`coordinatorToken`, `RunSeat.COORDINATOR`); see [0005](0005-renames-with-deprecated-aliases.md).
- A seat may not be named `director`; `orchestrator` is a reserved seat name.

## Reopen when

A third kind of session work appears that neither drives nor decides.
