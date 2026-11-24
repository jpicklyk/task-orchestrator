# Design

Design and architecture documents for Task Orchestrator: how users work with it, which entry points they use, and what the server, plugin and config must support for that. Terms follow [GLOSSARY.md](../GLOSSARY.md).

These documents record design intent and the evidence behind it. They are not implementation plans: a plan for one change stays in the gitignored `plans/` folder and is discarded once its work lands. When code and a document here disagree, the code is current and the document needs an update or a note that it is superseded.

## Documents

| Document | What it holds | Published view |
|---|---|---|
| [user-journey-map.html](user-journey-map.html) | The user journeys: the moments (Intend, Decide, Accept, Improve, Look, Learn, Setup), the dials, seven software-development journeys and the knowledge-base journey, with gaps by moment | [Strategic Journey Map](https://claude.ai/artifact/XZH4m8WqMH2TyvB3oMMW5V) |
| [entry-skills.html](entry-skills.html) | The user-facing entry skills, the interfaces each needs, decided calls, diagrams and the shared gaps | [Entry Skills Design](https://claude.ai/artifact/FCjDsifoWdKQNcYWy1f8xS) |
| [journey-config-fit.html](journey-config-fit.html) | What a project's config can express for each journey, the real gaps, setup's template library and the knowledge-base guard rails | [Journey Config Fit](https://claude.ai/artifact/1wPBpYW8hCtXKd1hdYwbRD) |
| [glossary-alignment.md](glossary-alignment.md) | Places where code, skills, hooks or docs disagree with the glossary, as candidate items | - |

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

- Method words (epic, story, charter, increment) are schema vocabulary, never Task Orchestrator concepts.
- The certainty dial is judgement, held in conversation. Its outcomes are recorded as rulings, assumptions and acceptance grants.
- Acceptance becomes a gate in the server, opt-in per type, satisfied the same way by every surface. Plan approval before the work is a conditional grant.
- Setup picks one pre-built, tested template from a reference library, domain first, and records which template and version it applied.
- Two domains: software development (primary) and keeping a knowledge base (first class, on the knowledge endpoint).
- The `next` contract stays as decided; further changes are held.

Still open: whether the acceptance gate is a note satisfied by an answered decision or a new lifecycle step.
