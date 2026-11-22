# Slop catalog

Slop is code that compiles, often passes tests and reads plausibly, yet makes the codebase worse to change: it was written toward the request instead of fitted into the system. Each instance is small; the cost is the sum over many changes. An audit uses this catalog to name what it finds in code that already exists.

Project standards, written conventions and decision records take precedence over every entry here. An entry is a candidate finding to weigh against the project's own rules, never a violation by itself. Entries marked lintable have a tell a script or a grep can find; entries marked judgement need a person to decide.

## Contents

- Fitting into the system: reinvented helper, convention drift, parallel versions
- Speculative structure: single-implementation abstraction, pattern with no force, unused options
- Shallow layers: pass-through wrapper, step-named modules
- Defensive noise: impossible-case guards, swallowed errors
- Legibility: generic names, narrating comments, leftovers, vocabulary drift
- Change hygiene: mixed-purpose commit, unrequested scope
- Source notes

## Fitting into the system

**Reinvented helper** (judgement). Tell: a second function that does what an existing one does under another name (date formatting, retry loop, path joining), found by searching the code for the nouns of what it does. Cost: two copies of one piece of knowledge drift apart. Move: delete it and call the existing one; if the existing one is wrong, fix it there. Source: Hunt and Thomas, *The Pragmatic Programmer* (DRY concerns duplicated knowledge, not duplicated text); Fowler, *Refactoring*, smell Duplicated Code.

**Convention drift** (judgement). Tell: code in one area uses an error style, logging call, file layout or naming scheme different from its neighbours with no recorded reason. Cost: readers hold two idioms, and the next change copies whichever it saw last. Move: rewrite to the local idiom; if the new idiom is better, migrate everything in one separate change. Source: Ousterhout, *A Philosophy of Software Design*, chapter on consistency.

**Parallel versions** (lintable: names ending in `V2`, `New`, `Old`, `Copy`, `_v2`, or two near-identical files). Tell: `fooV2` beside `foo`, a copied module with one change. Cost: nobody knows which is live, so both get maintained. Move: migrate callers to one, then delete the other. Source: Danilo Sato, "ParallelChange", martinfowler.com (2014), for doing that migration in safe steps.

## Speculative structure

**Single-implementation abstraction** (judgement). Tell: an interface, abstract class or port with exactly one implementation and no test double. Cost: every reader pays an extra hop for variation that does not exist. Not slop when a dependency rule requires it (core logic must not import storage or transport, so a port stands between them) or when a test fake implements it; check the project's layering rules and its tests before reporting. Move: otherwise inline it, and reintroduce it when a second real implementation appears. Source: Fowler, *Refactoring*, smell Speculative Generality; Beck's rules of simple design (fewest elements).

**Pattern with no force behind it** (judgement). Tell: a factory, builder or strategy whose variation never occurs; the pattern's name is in the code and its reason is not. Cost: ceremony without the problem the pattern solves. Move: a direct constructor or function call. Source: Gamma et al., *Design Patterns* (each pattern's Applicability section states the problem it solves).

**Unused options** (lintable in part: a parameter or flag no call site passes). Tell: config flags, optional parameters or hooks that no caller sets. Cost: a wider interface and a larger test matrix for nothing. Move: remove the parameter and inline its default. Source: Fowler, Speculative Generality.

## Shallow layers

**Pass-through wrapper** (judgement). Tell: a function or class whose body forwards to one other call with the same arguments. Cost: an interface as large as its implementation adds a hop and no leverage. Move: inline it. The test is whether its surface adds anything callers could not do themselves with the call underneath (a different abstraction, hidden detail, a decision made once); if not, it is only an extra hop. Source: Ousterhout, *A Philosophy of Software Design* (deep modules; pass-through methods); Fowler, smell Middle Man.

**Step-named modules** (judgement). Tell: modules named for steps of one process (read, parse, validate) that each know the same file format. Cost: one design decision leaks across modules, so a format change touches all of them. Move: merge around the shared knowledge and hide the format behind one module. Source: Parnas, "On the Criteria To Be Used in Decomposing Systems into Modules" (1972); Ousterhout, information hiding and leakage. Over-splitting a single idea into many tiny files is the same cost seen from navigation; that link is our judgement, not a quoted claim.

## Defensive noise

**Impossible-case guards** (judgement). Tell: null or shape checks on values the type or the caller already guarantees; the same validation repeated at every layer. Cost: hides the real contract and implies states that cannot happen. Move: validate once at the boundary and pass a typed value inward. Source: Alexis King, "Parse, don't validate" (2019); the maxim to make illegal states unrepresentable, from Yaron Minsky.

**Swallowed or masked errors** (lintable: an empty catch, a log-only catch, a broad catch around a whole body, a default returned from a catch block). Tell: a failure is caught and the program carries on as if it had succeeded. A designed default for a value that is legitimately absent is fine; a default that hides a failure the caller needed to know about is the slop. Cost: bugs surface far from their cause, or never. Move: let it fail fast; handle an error only at the layer that can act on it. Source: Jim Shore, "Fail Fast", *IEEE Software* (2004); Hunt and Thomas, *The Pragmatic Programmer* (crash early).

## Legibility

**Generic names** (lintable against a per-project list: `data`, `result`, `info`, `Manager`, `Helper`, `utils`, `common`). Tell: a name that would fit almost any value or module in the codebase. Cost: a name that fits anything tells the reader nothing and defeats search. Move: rename to the domain concept; if no honest name comes, the design is murky. Source: Ousterhout, choosing names; Fowler, smell Mysterious Name.

**Narrating comments** (judgement; a grep for change-log phrasing finds some). Tell: comments restating the code, comments recording a change ("updated to fix the bug"), comments addressed to whoever asked for the work. Cost: noise that goes stale; history belongs in version control. Move: delete; keep comments that say why or what is not obvious. Source: Ousterhout, comments should describe what is not obvious from the code.

**Leftovers** (lintable: commented-out code, debug prints, TODO with no owner, unused imports or exports). Tell: code that nothing runs or reads, and notes to self left in the source. Cost: the next change treats whatever exists as something to copy. Move: delete. Source: Hunt and Thomas (broken windows); Fowler, *Refactoring* (the refactoring Remove Dead Code).

**Vocabulary drift** (lintable when the glossary lists words to avoid; otherwise judgement). Tell: code, notes or docs name a glossary concept with a word the glossary rejects, or coin a second name for a defined term. Cost: one concept under two names, so search finds half the uses and readers assume two things. Move: rename to the glossary term; if the new word is better, change the glossary first, as an owner decision. Source: Evans, *Domain-Driven Design* (ubiquitous language).

## Change hygiene

**Mixed-purpose commit** (judgement; read the history of the file under audit). Tell: a behaviour change committed together with renames, reformatting or restructuring of unrelated code. Cost: neither a reviewer nor a later bisect can separate behaviour from structure. Move: split into a tidying commit and a behaviour commit. Source: Beck, *Tidy First?* (2023), on keeping structure changes apart from behaviour changes.

**Unrequested scope** (judgement). Tell: behaviour, options or files that no specification or work item asked for. Cost: untested surface that nobody owns. Move: remove it, or raise it as its own item. Source: our judgement; Fowler, Speculative Generality is the nearest named smell.

## Source notes

A source names where an idea comes from, not a claim that the source makes every point of the entry. Where an entry says our judgement, the connection is this catalog's own.
