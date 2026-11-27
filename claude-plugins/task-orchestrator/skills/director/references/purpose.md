# Why the director layer exists

## The problem

One session used to be both the user's point of contact and the orchestrator of every run. That filled
its context, made it unavailable while a run was in flight, and left held state fragile across
compaction. The session layers split those jobs so each context is purposeful and clearable, token
cost stays proportional to the work, and the server (not a prompt) holds state and boundaries.

## The layers

| Layer | What it is | Owns | Lifetime |
|-------|------------|------|----------|
| Seat | One agent on one phase of one item (planner, implementer, test author, reviewer) | Its seat's notes; the entry seat also makes the one phase entry | One dispatch, fresh context |
| Orchestrator (default) | The user's own session | Planning, sizing, dispatching seats, every later transition; when it opens a run it is also that run's orchestrator | The session |
| Run's orchestrator | Whoever presents a run's token | One run: assign seats, advance members, post-run, report | One run, disposable; resumes from the run view and its run files, never from conversation |
| Director (opt-in) | The layer a session plays in director mode | Answering decisions across runs and acting on the board at the user's instruction (the board is the user's view in both session modes), staying free to talk to the user | The session; rebuilds its view from the server |

## Why add a director

1. A lean, long-lived context: it holds ids, counts and short reasons, never code or note bodies.
2. One decision inbox across runs.
3. The user-facing session stays free to answer while work runs in the background.
4. More parallel and longer-running work than one session can hold.

## When not to use one

Small work or a single short wave. Orchestration mode is enough, and a director adds a layer and its
cost. A run of one or two items needs no separate orchestrator even under a director.

## Limits

The director cannot plan or read files, waves on one project root run at once only when their members, files and worktrees do not overlap, and its
orchestrators run as a background agent in the director session (tier 2), as a Code Mode orchestrator
subagent in the director session (tier 2b), as a separate desktop session
(tier 1) or as a separate headless `claude -p` process that a background agent starts (tier 3); tier 4
has none. The full list is in
[coordinator-tiers.md](coordinator-tiers.md), section "Limits".
