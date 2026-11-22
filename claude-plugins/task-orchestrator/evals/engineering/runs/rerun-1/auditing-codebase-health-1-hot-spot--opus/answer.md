I wrote `audit.md` and didn't touch any other file. The priorities come from how often each file has changed in git history and how complex its code is:

1. **`src/pricing.js` — Act now.** It's the hotspot: it was changed in every one of the 14 commits after the initial one, and half of those were bug fixes. It stacks tier, seasonal, clearance and promo discounts in one-off branches using floating-point math, and nothing tests it.
2. **`src/promo.js` — Plan it.** It changes together with `pricing.js` (7 commits), and new promo rules keep landing in its hardcoded `PROMOS` list. It's best redesigned as part of the pricing work rather than separately.
3. **`src/legacyReport.js` — Watch.** It's the ugliest code here (single-letter names, deeply nested ifs, a module-level running total). But nothing has changed it since the initial commit and nothing imports it, so cleaning it up wouldn't pay off yet.

The repo has no test files, even though `package.json` has a `node --test` script. Adding tests around `priceFor` should come before refactoring rank 1. There's also clutter to clear: most of the commits after the initial one added only an empty `// revision N` comment line to each file they touched, and those lines are still there.
