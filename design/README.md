# Design

Design and architecture documents for Task Orchestrator: how users work with it, which entry points they use, and what the server, plugin and config must support for that. Terms follow [GLOSSARY.md](../GLOSSARY.md).

These documents record design intent and the evidence behind it, and [decisions/](decisions/README.md) records why each settled choice was made. They are not implementation plans: a plan for one change stays in the gitignored `plans/` folder and is discarded once its work lands. When code and a document here disagree, the code is current and the document needs an update or a note that it is superseded.

## Documents

| Document | What it holds | Published view |
|---|---|---|
| [user-journey-map.html](user-journey-map.html) | The user journeys: the moments (Intend, Decide, Accept, Improve, Look, Learn, Setup), the dials, seven software-development journeys and the knowledge-base journey, with gaps by moment | [Strategic Journey Map](https://claude.ai/artifact/XZH4m8WqMH2TyvB3oMMW5V) |
| [entry-skills.html](entry-skills.html) | The user-facing entry skills, the interfaces each needs, decided calls, diagrams and the shared gaps | [Entry Skills Design](https://claude.ai/artifact/FCjDsifoWdKQNcYWy1f8xS) |
| [journey-config-fit.html](journey-config-fit.html) | What a project's config can express for each journey, the real gaps, setup's template library and the knowledge-base guard rails | [Journey Config Fit](https://claude.ai/artifact/1wPBpYW8hCtXKd1hdYwbRD) |
| [glossary-alignment.md](glossary-alignment.md) | Places where code, skills, hooks or docs disagree with the glossary, as candidate items | - |
| [architecture-breakdown.md](architecture-breakdown.md) | The work packages that carry the settled design, their layers, dependencies and order, and the decisions they still need | - |
| [decisions/](decisions/README.md) | Decision records: one dated, never-edited record per architectural decision, superseded rather than changed | - |

Open an HTML file directly in a browser, or use the published view. The published views are private to their owner.

## Evidence

[evidence/](evidence/) holds the working files behind the config fit. Line numbers in them are as of the commit each names and go stale as the code moves.

| File | What it holds |
|---|---|
| [journey-stages.txt](evidence/journey-stages.txt) | Every journey stage, generated from the journey map |
| [journey-config-requirements.md](evidence/journey-config-requirements.md) | Journey stages turned into config requirements (R1-R27) |
| [config-catalogue.md](evidence/config-catalogue.md) | What the config model can express today, with sources |
| [journey-config-fit.md](evidence/journey-config-fit.md) | First-pass grades of each requirement |
| [journey-config-critique.md](evidence/journey-config-critique.md) | The critic's re-grading against source, and R28-R34 |
| [journey-config-sketches.md](evidence/journey-config-sketches.md) | One config sketch per development journey, shared blocks and composition traps |
| [journey-knowledge-base.md](evidence/journey-knowledge-base.md) | The knowledge-base journey, its leaks (KL1-KL29), R35-R50 and the guard rails |

## Settled for the architecting phase

Each settled choice has a decision record; the [index](decisions/README.md) lists them all. The ones this design phase rests on most:

- [0006](decisions/0006-method-words-are-schema-vocabulary.md) Method words are schema vocabulary, not tool concepts.
- [0007](decisions/0007-certainty-is-judgement.md) The certainty dial is judgement held in conversation.
- [0008](decisions/0008-acceptance-is-a-server-gate.md) Acceptance becomes a server gate; plan approval is a conditional grant.
- [0009](decisions/0009-setup-applies-tested-templates.md) Setup applies one pre-built, tested template.
- [0010](decisions/0010-two-domains.md) Two domains: software development and keeping a knowledge base.
- [0004](decisions/0004-next-means-startable.md) `next` returns an item a build seat can start now; further changes are held.

Still open: whether the acceptance gate is a note satisfied by an answered decision or a new lifecycle step (see 0008).
