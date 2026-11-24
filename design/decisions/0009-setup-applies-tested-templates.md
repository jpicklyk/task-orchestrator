# 0009 Setup applies one pre-built, tested template from a reference library

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** [journey-config-fit.html](../journey-config-fit.html) "How setup chooses a config"; [evidence/journey-config-sketches.md](../evidence/journey-config-sketches.md)

## Decision

The setup entry skill holds a reference library of templates and the use cases each supports. It checks which tool profile it is connected to, asks the domain first, then two to four questions about the work (each with a default), and applies one template, steering included. Templates ship pre-built and validated in CI; setup never composes blocks live. Setup records which template and version it applied, and an upgrade leaves the user's own edits alone.

## Context

Seven sketch configs, one per development journey, showed the same ten building blocks recurring, and five composition traps where blocks that each look right combine wrongly (for example, a trait that adds a seat leaves the type's unseated required notes unowned, and run-wave then skips the item). Users who run setup never read the config, so they cannot spot a trap. The manage-schemas library already has ten profiles and a classifier; setup does not call it.

## Alternatives rejected

- **Compose a config live from blocks.** Walks users into the composition traps.
- **Fixed stacks by kind of project (light, cadence, exploratory).** Uncertainty is judgement ([0007](0007-certainty-is-judgement.md)), not a kind of project; question and probe types are add-ons for any domain.

## Consequences

- Steering is part of each template, so a knowledge template does not inherit software process.
- The record of the applied template is what "stay current" compares against when the plugin changes, and what learn explains from.
- Upgrades reuse the bundled-rules policy: a copy matching a known old version is replaced, a customised copy is left and reported.
- The ongoing cost is keeping templates correct as the plugin changes and merging upgrades into edited configs.
