# Gaps

A gap is something a reader needs and no document holds. Report one only when a reader would pay for its absence; a section nobody needs is not a gap. The move for each is to add what is missing, kept as small as a cut would leave it.

- **A non-obvious decision with no record.** The code does something a reader would undo or doubt, and no document gives the reason. Move: add a short decision record, or one line in the document that owns the area. Source: Nygard, "Documenting Architecture Decisions" (2011).
- **An invariant a guard enforces and no document states.** A test, a lint rule or a hook fails on a rule that no document names. Move: state the rule once, in the home for contributors, naming the guard. Source: matklad, "ARCHITECTURE.md" (2021), on stating invariants.
- **A user task with no how-to.** A task users do through the public surface has no page. Move: add the how-to, not a tour of the surface. Source: Procida, Diataxis framework, on how-to guides.
- **An agent file with no verify command.** An agent cannot tell how to check its own change. Move: add the one command, in the agent instruction file. Source: Anthropic, Claude Code documentation on memory files, on listing the commands an agent needs.

A gap found by reading code only is a lower confidence finding. Ask whether history shows people asking, or reverting, because of it.
