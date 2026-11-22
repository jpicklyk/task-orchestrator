I wrote `audit.md` and didn't touch any other file. The ranking comes from reading the code, the git history (11 commits) and GLOSSARY.md.

1. **`src/checkout.js` — Act now.** It's the most-changed file: every commit since the initial one touched it. It has no tests. It also calls the main domain object a "purchase", but the glossary says that word shouldn't be used and the term is `Order`. Its ids come from `Date.now()`, so two checkouts in the same millisecond get the same id.
2. **`src/purchaseLog.js` — Plan it.** It changes alongside checkout.js in 6 of the 10 commits and uses the same wrong "purchase" naming; per the glossary it belongs in `orders/`. It stores purchases in a plain array inside the module, so nothing is saved and tests can't reset it. Swapping that for a proper order store is a coordinated change across both files, so it needs planning.
3. **`src/carts.js` — Watch.** It's small and has only changed twice. `addLine` doesn't check its quantity or price inputs, so watch it as cart features grow.

Two other things apply to the whole codebase, not one file. There are no test files, even though `npm test` is set up to run `node --test`. And the source files end in leftover `// revision N` comments that should be removed.
