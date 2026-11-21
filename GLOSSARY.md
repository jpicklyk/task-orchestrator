# Task Orchestrator

Work items move through gated phases; seats are the agents that fill each phase's notes. These terms name the concepts that code, schemas and reviews share.

## Language

**Seat**:
A named position in an item's workflow, declared in a schema for one phase, that one agent fills.
_Avoid_: role (that is the phase a note belongs to), agent

**Seat-aware schema**:
A schema that declares at least one seat.
_Avoid_: seated schema

**Owning seat**:
The seat a note belongs to: the seat its schema entry names, when that seat is declared for the note's own phase. In a schema that is not seat-aware, the named seat stands as written. A note with no owning seat is unowned.
_Avoid_: note owner (that is the identity that last wrote the note), seat ownership
