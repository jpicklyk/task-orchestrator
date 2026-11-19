# Architecture fit

A change that works but sits in the wrong module, copies an existing helper or invents an
abstraction nobody needs costs more later than it saved. Settle fit while planning.

## Find the stated rules

Look for the project's written layering and dependency rules: an architecture document, a module
layout, automated checks on imports or package cycles. Note which direction dependencies may point
and which packages may not import which. Then name the module the change belongs in and why.

Self-check: can you quote the rule that allows each new dependency the change creates? If the
project states no rule, say so and follow the dominant direction visible in the existing imports.

## Reuse before build

Before proposing a new helper, type or pattern, search for one that already exists: by name, by
behaviour and by the shape of its callers. Say what you found and why it is or is not reused. If
it nearly fits, prefer extending it to writing a second copy, since two copies diverge.

Self-check: for every new helper, can you name the closest existing one and the specific reason
it does not serve?

## No speculative abstraction

Build for the cases the plan names. An abstraction (an interface, a base type, an extra parameter,
a layer) is justified by one of two things: a second real user that exists today, or a stated
boundary reason, such as a dependency rule that keeps one side from knowing the other, or a seam
a test needs. With neither, it is speculative: do not add it for a second use that may arrive
later.

Self-check: for each new abstraction, name the second user that exists today or the boundary
reason. If there is neither, remove it.

## Wiring sites

A new dependency or registration is often constructed or listed in more than one place: a
composition root, a factory, a registry, a list of handlers, a test fixture. A new constructor
parameter with a default value compiles without being passed, so the compiler will not show a
missed site. Search for every place the type is built or registered and name each one in the
plan.

Self-check: does the plan list every construction and registration site, with the search that
found them (see [claims and sweeps](claims-and-sweeps.md))?
