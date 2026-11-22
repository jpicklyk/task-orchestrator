---
name: defining-project-terms
description: "Keeps a project's glossary agreed and current: decides whether a word earns an entry, writes the entry in the project's format, tests a term against its nearest neighbour, and decides when a project needs more than one glossary or when a decision needs a record. Use when a request or a review turns on what a project word means, when two words compete for one concept, when code introduces a new domain concept, or when writing or editing a GLOSSARY.md. Silent for a project that has no glossary until its owner agrees a first term."
user-invocable: false
---

# Defining Project Terms

A glossary exists so that people and agents who read only part of a project still use its words the same way. It is short on purpose: every entry is a promise to keep the code, the notes and the owner's own speech in line with it.

Where the project documents its own glossary conventions, they take precedence over this skill.

## When it applies

- **A project with a glossary:** read it before naming anything in the domain (types, files, fields, notes, reports). Use its terms; never use a word an entry lists under Don't call it.
- **A project without a glossary:** stay silent about glossaries; do not suggest one unprompted. Only when the owner agrees a first term that passes the test below is the file created, and that term creates it in the same change as the code that needs it.
- **A concept the glossary lacks:** propose an entry; do not coin one silently. The owner approves it in review.

## Instructions-file lines

When a project's first glossary is created, the same change adds these lines to the project's agent instructions file (CLAUDE.md, or AGENTS.md on other hosts), which every session and subagent loads. Adapt the wording; keep every point:

```markdown
## Glossary

Domain terms are defined in [GLOSSARY.md](GLOSSARY.md).
- Use the glossary's term for a concept; never use a word an entry lists under Don't call it.
- Existing code that still carries such a word is named in the report and left alone, unless the change already touches that code.
- When a concept has no entry, propose one; do not coin a name silently.
- Update the glossary in the same commit as the code that adds or renames a concept.
```

## Does it earn an entry

A term gets an entry only if at least one holds:

1. The project uses it differently from its everyday meaning.
2. Two words compete for it.
3. It has already caused confusion.

A word everyone reads the same way gets no entry. A glossary of obvious words buries the few that matter. Method: [testing a term](references/testing-a-term.md).

## Entry format

One heading per term. The meaning is required; the other fields are optional and used only when they say something:

- **Meaning** (required): what the term is, in terms a newcomer can apply, in as few words as keep it exact. A meaning that needs a paragraph is usually two terms.
- **Distinct from**: the nearest neighbour and the line between them.
- **Don't call it**: words that must not be used for this thing. Old names go here after a rename.
- **In code**: the symbol or folder name.
- **Example**: the concrete case that settled the meaning.

A worked entry and the wording rules: [entry shape](references/entry-shape.md).

## Moments

1. **Before naming.** Look the concept up. If an entry covers it, use that term and its In code name. If two words in the request point at one concept, use the glossary's word and say which you chose.
2. **When a word has two meanings.** Split it: name each meaning separately and give each its own entry, rather than one entry with "also means". Method in [testing a term](references/testing-a-term.md).
3. **When code introduces or changes a concept.** The entry change belongs in the same commit as the code. A rename moves the old name to Don't call it.
4. **When the project is large.** One glossary per area where a word genuinely differs, not one that tries to cover everything: [multiple glossaries](references/multiple-glossaries.md).
5. **When a decision would otherwise be undone.** A reader who finds a choice odd will reverse it. Record the decision when a reader without the reasoning would likely undo it: [decision records](references/decision-records.md).

## Checks before finishing

- Every new name in the domain uses a glossary term, or the change proposes a new entry.
- No word from a Don't call it field appears in the names, comments, tests, notes or report you write. Existing code that still carries one is named in the report and left alone, unless the change already touches that code.
- Each entry added or changed passes the entry test and carries only the fields it needs.
- If the project has no glossary, the change adds none and mentions none, unless the owner has agreed a first term, which creates the file in that change.

## References

- [entry-shape.md](references/entry-shape.md): the entry fields, a worked entry, wording rules
- [testing-a-term.md](references/testing-a-term.md): the entry test, separating a word with two meanings, the nearest-neighbour case
- [multiple-glossaries.md](references/multiple-glossaries.md): when and how to split
- [decision-records.md](references/decision-records.md): when a decision earns a record

## Sources

The idea of one shared vocabulary used in speech and code (ubiquitous language), the bounded context and the context map are Eric Evans, *Domain-Driven Design* (2003). Decision records are Michael Nygard, "Documenting Architecture Decisions" (2011). The entry test, the entry fields and the checks above are this skill's own design, not taken from either.
