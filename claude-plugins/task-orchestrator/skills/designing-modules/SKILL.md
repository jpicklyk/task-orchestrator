---
name: designing-modules
description: "Decides and judges the shape of code: where a module boundary goes, what it hides, which way dependencies point, who owns state, how errors are designed. Use when planning a change that adds a module, class, package, interface or pattern, restructures code, or touches a widely used surface, and when assessing the design of existing code."
user-invocable: false
---

# Designing modules

A module is any unit with a surface and a body: a function, a class, a file, a package. Good design makes the next change cheap: one change touches one place, and a reader can trust a module's surface without reading its body.

Where the project documents its own design rules or decisions, they take precedence over every check below: code that follows a documented project rule is not a finding.

## When to run the checks

- **Planning a change:** run them on the modules the plan adds, splits, merges or whose surface it changes, and write the answer into the plan (the secret, the force, the owner), not just the conclusion.
- **Assessing existing code:** run them on the code in question, apply each "Look for" list, and report with the finding format at the end.

Each check gives what to decide when designing and what to look for in code that exists.

## 1. What does this module keep secret?

Draw a boundary around one decision likely to change (a format, a storage choice, a protocol, a policy) and keep it inside. (Parnas, "On the Criteria To Be Used in Decomposing Systems into Modules", 1972.)

- **Look for:** the same knowledge in several modules: two files that parse one format, build one key, or know one table's columns. A change to that format that would touch three files is a leaked secret.
- **Look for:** modules split by the order things happen (read, parse, validate, save), each knowing the same data shape. Sequence is a poor reason for a boundary; shared knowledge is a strong reason to merge. (Ousterhout, *A Philosophy of Software Design*, "Information Hiding (and Leakage)".)

## 2. Inline check: does this module earn its surface?

Imagine folding the module into its callers. If complexity disappears, it was a pass-through. If complexity spreads to every caller, it does real work.

- **Look for:** functions or classes that forward to one call with the same arguments; layers that only rename; a surface nearly as large as the body behind it.
- **Look for the opposite too:** one concept spread over many tiny files or functions, so a reader opens five files to follow one behaviour. Fold them together.

## 3. Name the force before the pattern

A pattern answers a pressure: this varies by vendor, this must be swappable in tests, this changes on a different schedule. If the pressure cannot be named, the pattern is ceremony.

- **Look for:** an interface, port or abstract class with one real implementation and no test double; a factory, registry or strategy with one case; options and hooks no caller sets. A test fake counts as a second implementation; a hypothetical future one does not.
- **Do not demand** an abstraction for one stable case. Duplication is cheaper than the wrong abstraction, so wait until a third occurrence shows the shared shape (the "rule of three", our wording of a common rule of thumb). (Metz, "The Wrong Abstraction", 2016; Fowler, *Refactoring*, "Speculative Generality".)

## 4. Which way do dependencies point?

Dependencies point toward code that changes less: policy and domain at the centre, IO and frameworks at the edge. Wiring happens in one place at startup. No cycles. (Martin, *Clean Architecture*, the dependency rule and the component principles.)

- **Look for:** core logic importing transport, storage or framework types; packages that import each other; objects constructed deep inside logic instead of passed in; a module that cannot be tested without starting the world.
- **Third-party services:** wrap the vendor behind a narrow interface you own and fake that interface in tests; do not mock the vendor's own client (our judgement).

## 5. Who owns this state?

Every piece of mutable state has one owner that enforces its invariants. Validate input once, where it enters, and pass a typed value inward. (King, "Parse, don't validate", 2019.)

- **Look for:** the same field written from several modules; invariants re-checked at every layer; booleans or strings standing for states a type could make impossible to mix up; caches with no clear owner or invalidation rule.

## 6. Can this error be designed away?

Prefer interfaces where the error cannot happen (an idempotent delete, a default that is always valid) over error paths every caller must handle. Where an error is real, handle it at the layer that can act on it and fail fast elsewhere. (Ousterhout, "Define Errors Out of Existence"; Shore, "Fail Fast", 2004.)

- **Look for:** catch blocks that log and continue, or return a default that hides failure; the same error translated at every layer; callers forced to check flags the callee could have avoided.

## 7. Will this survive the next likely change?

Once a surface is used, every observable behaviour of it will be depended on, documented or not. (Hyrum's law, in *Software Engineering at Google*, 2020.) Change a widely used surface in three steps: add the new form beside the old, move the callers, remove the old (often called parallel change or expand and contract).

- **Look for:** a widely used surface with flags or optional parameters bolted on for one caller each; boolean or mode parameters that switch between two behaviours (two functions hiding in one); old and new versions left side by side.

## Reporting a design finding

For each finding give: the location (file and symbol), the check it fails (1-7), the concrete cost (which future change it makes expensive, and how you know: churn, duplication count, call sites), the proposed restructuring, and your confidence. Skip a finding you cannot tie to a concrete cost.
