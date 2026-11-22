I wrote `audit.md` with the three candidates ranked, and I didn't edit any other file.

1. **`src/checkout.js` (Act now):** It changes in nearly every commit and has no tests. It builds a "purchase" where `GLOSSARY.md` says "Order". Its ids come from `Date.now()`, which can collide.
2. **`src/purchaseLog.js` (Plan it):** It stores purchases in an in-memory list, so everything is lost on restart. It uses the same "purchase" name instead of "Order" and changes alongside `checkout.js`.
3. **`src/carts.js` (Watch):** It is small and rarely changes. `addLine` doesn't validate quantity or price.

The codebase is only three source files and 11 commits, so the ranking rests on how often each file changes and on the naming and storage problems above, not on a large body of code.
