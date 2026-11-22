FINDING src/cart/cart.js:4 | Leftover debug console.log dumps every cart's lines (customer data) to stdout on each total calculation; remove it.
FINDING src/cart/cart.js:5 | Commented-out sorted-reduce code left in; dead code that should be deleted.
FINDING src/cart/cart.js:8 | cartTotal never accepts or passes a discount, so the headline feature ("discount support") is not reachable via the existing API; only the new duplicate cartTotalV2 uses it.
FINDING src/cart/totalV2.js:3 | cartTotalV2 duplicates cartTotal with a different name and slightly different behavior; two parallel total functions will drift. Add the discount parameter to cartTotal instead of a V2 file.
FINDING src/cart/price.js:2 | Null/undefined, non-numeric and negative-qty guards silently return 0, contradicting the CLAUDE.md contract that lines are validated at the API boundary (src/api/validate.js). This is redundant defensive code and hides bad data as a zero-priced line instead of failing loudly, which can under-charge.
FINDING src/cart/price.js:6 | discountPercent is unvalidated: values >100 or negative produce negative totals or price increases. Rounding is also applied per line, so the sum of discounted lines can differ by cents from a discount on the cart total.
FINDING src/cart/price.js:1 | No tests were added for discounts, rounding or edge cases in this commit.
VERDICT: changes
