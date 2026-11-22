# domain-language

When the project keeps a glossary (`GLOSSARY.md`), its words are the project's vocabulary.

1. **Use the glossary's term** in names, comments, notes, commit messages and reports for the thing it defines.
2. **Never use a word an entry lists under Don't call it** in what you write. Where existing code still carries one, name it in your report and leave it, unless the change already touches that code; renaming elsewhere is its own change.
3. **Propose a missing term; do not invent one.** When the work needs a name for a concept the glossary lacks, or finds two words for one concept, put the proposed entry in the change for the owner to approve.
4. **A new or changed term goes into the glossary in the same commit as the code that introduces it.**

A project with no glossary is not asked to start one by this rule; it stays silent until the owner agrees a first term.
