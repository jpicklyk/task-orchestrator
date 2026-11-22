# Multiple glossaries

One glossary is the default. Split only when one word means different things in different parts of the project and both meanings are correct where they are used. The underlying ideas are Eric Evans's bounded context (a part of the system inside which one model and one vocabulary hold) and context map (how those parts relate and translate), from *Domain-Driven Design* (2003); a glossary per area is the lightweight form of it.

## When to split

- The same word names different concepts in two areas that each have their own code and their own people (for example "account" in billing and in sign-in). A single entry would need "in billing it means ... but in sign-in it means ...", which is two entries forced into one.
- The project is several deployable parts whose owners do not share a vocabulary.

Do not split because the file is long. Long means entries are failing the entry test; remove those first.

## How to split

- Keep the shared words in the root `GLOSSARY.md`: the terms that mean one thing everywhere.
- Give each area a `GLOSSARY.md` in its own folder holding the words that are local to it, and have the root file name the area files.
- A local entry may reuse a word a different area also uses. State, in each, which area it belongs to and point to the other under Distinct from.
- Inside an area use that area's glossary; across areas, name the area when using an ambiguous word ("billing account").
- Where one area's code meets another's, the boundary code uses the receiving area's word, and the translation is in one place, noted in both glossaries.

## Keeping it honest

A term that appears in two area glossaries with the same meaning belongs in the root file; move it. A term in the root file that an area file contradicts was never universal; move it down.
