I added `summarizeInvoice(invoice)` and a test for it, but I couldn't run the tests. Both attempts to run `npm test` were blocked for approval, so the test has never run.

- **`src/billing/summary.js`**: each line prints as `Widget x2: $25.00`, followed by `Subtotal:`, `Tax:` and `Total:` rows. Amounts go through the existing `formatAmount`, using the invoice's currency.
- **`test/summary.test.js`**: builds an invoice with `createInvoice` using two lines and 10% tax, and checks the exact output text.

Invoice lines in `createInvoice` have `unitCents` and `qty` but no defined name field, so I made the label come from `description`, then `name`, then `"Item"`. If your lines use a different field, change that one line.

You can run `npm test` yourself to check it.
