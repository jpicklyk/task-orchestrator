# Why the director exists

## The problem

One session used to be both the user's point of contact and the coordinator of every run. That filled
its context, made it unavailable while a run was in flight, and left held state fragile across
compaction. The session layers split those jobs so each context is purposeful and clearable, token
cost stays proportional to the work, and the server (not a prompt) holds state and boundaries.

## The layers

| Layer | What it is | Owns | Lifetime |
|-------|------------|------|----------|
| Seat | One agent on one phase of one item (planner, implementer, test author, reviewer) | Its seat's notes; the entry seat also makes the one phase entry | One dispatch, fresh context |
| Orchestrator (default) | The user's own session | Planning, sizing, dispatching seats, every later transition; when it opens a run it is also that run's coordinator | The session |
| Coordinator | Whoever presents a run's coordinator token | One run: assign seats, advance members, post-run, report | One run, disposable; resumes from the run view and its run files, never from conversation |
| Director (opt-in) | A session in director mode | The board across runs, answering decisions, staying free to talk to the user | The session; rebuilds its board from the server |

## Why add a director

1. A lean, long-lived context: it holds ids, counts and short reasons, never code or note bodies.
2. One decision inbox across runs.
3. The user-facing session stays free to answer while work runs in the background.
4. More parallel and longer-running work than one session can hold.

## When not to use one

Small work or a single short wave. Orchestrator mode is enough, and a director adds a layer and its
cost. A run of one or two items skips the coordinator layer even under a director.

## Limits

The director cannot plan or read files, one wave runs per project root at a time, and its
coordinators live inside the director session (except a tier-1 session). The full list is in
[coordinator-tiers.md](coordinator-tiers.md), section "Limits".
