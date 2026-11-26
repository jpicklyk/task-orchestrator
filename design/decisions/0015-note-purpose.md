# 0015 Note purpose names the job a note does for the tool

**Status:** Accepted | **Date:** 2026-11-26 | **Source:** Boundary analysis 2026-11-26, owner answers (plans/boundaries/README.md, not tracked): the note-role seam accepted as the direction, decision 15 as revised after pass 9, revised decision 17 and decision 19

## Decision

A note entry may declare `purpose`, one value from a closed vocabulary the plugin owns and the server publishes in its config contract. Plugin code, workflow prompts and skills find the note that does a job by its purpose, never by a fixed key. The values are `closure-record`, `dispatch-provenance`, `findings-register`, `finding-dispositions`, `fix-cycle-rulings`, `test-plan`, `carry-in-dispositions` and `run-pointer` for the tool's own mechanisms, and `goal`, `finding` and `ruling` for the entry skills. The server parses, validates and serves the field and never acts on it. There is no acceptance-claims value: acceptance evidence travels on the acceptance decision itself.

## Context

Plugin mechanics need the note that does one job: the closure record the retrospective triages, the dispatch provenance the retrospective parses, the review's findings register and the orchestrator's finding dispositions, the fix-cycle rulings, the test plan whose scenario ids verify counts, the carry-in dispositions, the run pointer. Each consumer hard-coded a key that only this repository's own config defines. The server config defines none of them, and one key the plugin reads (`orchestrator-carry-in-dispositions`) is declared in no schema at all. On a project with only the server config, the retrospective triage reads the missing `session-tracking` note as a signal (`claude-plugins/task-orchestrator/hooks/retro-lib.mjs:586-588`), so every small scope owes a retrospective and `minItems` never skips one. [0006](0006-method-words-are-schema-vocabulary.md) already says plugin skills never assume a method's note keys.

The journey overlay showed that mechanism notes are not enough. Shape, accept and build-to-learn must locate the goal, the finding and the ruling without knowing what a project calls them, and the outcome of a judgement such as sizing or keep-or-throw needs a note to hold it ([0007](0007-certainty-is-judgement.md)). Three values close those gaps.

The field name `role` is taken: it is the note's phase. `skill` and `skill_mode` are the precedent for client vocabulary the server stores, validates and serves without acting on it.

## Alternatives rejected

- **Fixed keys, reserved by the server config's traits.** Freezes this repository's key names as platform names, cannot vary by type, and still fails on a server run without the server config mounted.
- **A key map in `extensions.claude-code`** (`notePurposes: {closure-record: session-tracking, ...}`). The map lives apart from the declaration it names, which is the drift the field exists to remove; it is per project, not per type, and does not travel with a trait or a template.
- **An `acceptance-claims` value with its own note.** A second note to hold evidence the decision record already carries: every raiser attaches evidence to the decision ([0002](0002-director-on-orchestrator-tools.md), [0003](0003-role-neutral-decisions-surface.md)). The acceptance gate ([0012](0012-acceptance-gate-note-form.md)) instead checks that the answered decision carries the claims in `context` and `links`.
- **Purposes for implementation notes and the test manifest.** Their only code consumer can address "every work note the base stage owns", and their content rule reaches the seat through the note's `skill:` binding.

## Consequences

- **Validation.** An unknown value is an error under strict parsing and a warning in migration mode. More than one note with the same purpose and the same owning seat in a resolved schema is a resolve-time warning, never fatal. `carry-in-dispositions` may appear once per owning seat: the planner's record and the orchestrator's late record.
- **Shipped defaults.** The server config ships the seat-less mechanism traits that carry the markers (`session-tracked`, `delegated`, `reviewed`, `has-carry-ins`), inert until a type names them ([config-layering.md](../config-layering.md)). A project override of one of them replaces it whole, so it restates the markers.
- **Transition.** For one release, a consumer that finds no marked note falls back to today's key and reports that it did (the alias pattern of [0005](0005-renames-with-deprecated-aliases.md)). After it, a type with no note of a purpose has that mechanism off, reported as a gap and never read as a signal.
- **Reading.** Consumers resolve keys once per root and type from the served schema (`items_read` `schema`, `config` `effective`), never per note read.
- **Server change.** The config JSON Schema, the key catalog, the parser and model, the served shapes and a server feature flag the plugin falls back on. No migration, no tool parameter, so no `@Doc` or tool golden change; the served-schema response-shape tests change.
- **Documentation.** `current/docs/config-reference.md` publishes the values; `config-format.md` says what reads each value and what happens without it. Public guides that teach `session-tracking` as the contract change in the same set. "Note purpose" becomes a glossary entry when the field lands.
- **Settles R24.** The config-fit requirement for the note that plays a part a method-neutral skill needs (R24 in [journey-config-fit.html](../journey-config-fit.html)) is met by the three journey values, in place of part skills.

## Reopen when

A mechanism or entry skill needs to find a note whose job no value names (add a value, never a key), or a project needs two notes with the same purpose for the same owning seat.
