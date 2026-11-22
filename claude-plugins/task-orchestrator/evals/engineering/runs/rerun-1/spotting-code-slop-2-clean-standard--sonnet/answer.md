I reviewed the most recent commit, "Mark an invoice as paid". The three new tests pass. I wrote my findings to `review.md` and the verdict is approve.

The commit follows the CLAUDE.md standards. It goes through the new `src/repos/invoiceRepo.js` instead of importing the store directly. It checks for `null` explicitly and throws an error that names the missing invoice id.

I found two minor issues:
- **`src/invoices/markPaid.js:3`:** `paidAt` isn't validated. If it's missing, `null` or `''`, the invoice stays falsy-unpaid and can be marked paid again, because the already-paid check is a truthiness test.
- **`test/markPaid.test.js:7`:** the tests share one in-memory store with fixed ids and never reset it. That works today but is fragile as more tests are added. There's also no test for a missing `paidAt`.

I didn't edit any file other than `review.md`.
