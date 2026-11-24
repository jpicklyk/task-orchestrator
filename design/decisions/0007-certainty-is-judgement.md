# 0007 The certainty dial is judgement held in conversation, not config

**Status:** Accepted | **Date:** 2026-11-24 | **Source:** [journey-config-fit.html](../journey-config-fit.html) (R32); [user-journey-map.html](../user-journey-map.html) dials

## Decision

How much must be decided before building, and how much deciding or acceptance a piece of work can waive, is judgement from the user or the agent: domain knowledge and experience, clarified through questions and answers. It is not declared in config. Its outcomes are recorded where they apply (a ruling, an assumption on the node, an acceptance grant), and a project's `human-only` decision classes are the limits no judgement may waive.

## Context

The config-fit investigation graded "the certainty dial" as the one requirement no config mechanism could express, and asked whether to add one. The owner's view: certainty cannot be declared ahead of time; it depends on the work, the people and what they know.

## Alternatives rejected

- **A per-scope config setting for certainty.** Declares in advance something that is only known in conversation, and would be wrong as soon as the work changes.

## Consequences

- Skills ask rather than read a setting: shape classifies open choices, and a seat proceeds on a stated assumption or blocks under a project rule.
- The record of an outcome matters: the next session must see that a judgement was made, or it gets re-made or lost.
