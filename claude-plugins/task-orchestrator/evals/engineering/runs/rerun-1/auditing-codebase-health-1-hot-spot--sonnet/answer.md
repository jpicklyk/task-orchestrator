I wrote `audit.md` with the three candidates, and I didn't edit any other file.

1. **`src/pricing.js` (Act now):** It has the most commits (15, mostly pricing bug fixes). It applies tier, seasonal, clearance and promo discounts in one order-dependent function. The repo has no tests, so any change to it is unprotected.
2. **`src/promo.js` (Plan it):** It has 8 commits and a hardcoded promo table with ad-hoc matching rules. It also reads `item.qty` from inside the pricing path, which ties it to cart behaviour.
3. **`src/legacyReport.js` (Watch):** It's the ugliest code, with one-letter names, module-level mutable state and deep nesting. It has been touched once and nothing depends on it, so cleanup can wait until someone has to change it.

The ranking is based on how often each file changes and how risky it is, not on how messy it looks. That's why the messiest file is ranked last. I judged this from commit counts and a read of the source, and I didn't run anything. Some of the churn in `pricing.js` and `promo.js` is just appended `// revision` comment lines, so the commit counts overstate real change.
